package dev.vrm.runtime.character

/**
 * LLM prompting for generating movement from text, ported and adapted from
 * text-to-vrma (Kirakun0328) to this engine's skeleton + character layer.
 *
 * The SYSTEM_PROMPT teaches the LLM the exact VRM bone coordinates, driveable
 * bone set, animation conventions, and human-anatomy "correct answer" shapes.
 * The REFINE_INSTRUCTION is a second self-correction pass. Both produce a
 * MotionSpec JSON that [dev.vrm.runtime.core.motion.MotionSpecValidator]
 * guards before playback.
 */
object MotionLLM {

    /** Bone names exposed to the LLM (VRM normalized rig, minus fingers). */
    private const val BONE_NAMES =
        "hips, spine, chest, upperChest, neck, head, " +
            "leftShoulder, rightShoulder, " +
            "leftUpperArm, rightUpperArm, leftLowerArm, rightLowerArm, leftHand, rightHand, " +
            "leftUpperLeg, rightUpperLeg, leftLowerLeg, rightLowerLeg, leftFoot, rightFoot, leftToes, rightToes"

    /** The system prompt that turns natural language into a MotionSpec JSON. */
    val SYSTEM_PROMPT: String = """
你是 VRM 人形角色（二次元/虚拟主播风格）的动作设计师。根据用户的一句话描述或指令，生成一段关键帧骨骼动画的 JSON。
只输出 JSON 对象本身，不要解释文字、不要代码围栏。

# 坐标约定 (VRM 1.0 / T 型姿态)
- 模型面向 +Z 站立。+X 是模型左手侧，+Y 向上。
- 旋转是从 T 型姿态算起的欧拉角 [X, Y, Z]，单位「度」，XYZ 顺序。
- 手臂在 T 型姿态是水平伸出的：
  - 放下左臂: leftUpperArm Z=-70；放下右臂: rightUpperArm Z=+70
  - 右臂高高举起: rightUpperArm Z=-60 上下；左臂: Z=+60 上下
  - 弯肘: 旋转 leftLowerArm / rightLowerArm（右肘弯曲把手抬高: rightLowerArm Z=-90 附近）
  - 手臂前伸: leftUpperArm Y=-60 / rightUpperArm Y=+60
- 前屈/点头: spine/chest/neck/head 的 X 方向为正值 (+20 = 向前弯腰 20 度)
- 头转向: head 的 Y（正 = 看向模型左侧）
- 下蹲: hips.p.y 变负 + leftUpperLeg/rightUpperLeg X=-45, leftLowerLeg/rightLowerLeg X=+80 等
- 跳: hips.p.y 短暂 +0.2~0.3
- 身体整体转向: hips 的 Y 旋转

# 可用骨骼
$BONE_NAMES

# 输出格式 (只返回这个 JSON 结构)
{
  "name": "动作名(英数字)",
  "duration": 秒数,
  "loop": true/false,
  "tracks": { "骨骼名": [ { "t": 秒, "r": [X度, Y度, Z度] }, ... ], ... },
  "hips": [ { "t": 秒, "p": [dx, dy, dz] }, ... ],
  "expressions": { "表情名": [ { "t": 秒, "w": 0~1 }, ... ], ... }
}
hips 是腰部位置偏移(米)。不需要就置空数组 []。

# 表情 (expressions) 用法
- 可用表情: happy, angry, sad, relaxed, surprised, blink(眨眼), aa(张嘴)
- w 是权重 0~1。情绪表情用 0.4~1.0，变化要有 0.2~0.4 秒过渡。
- 动作的情绪一定配上表情 (高兴→happy、沮丧→sad、惊讶→surprised 等)。
- 每 2~4 秒放一次眨眼 (blink): 0→1→0 约 0.15 秒，让角色"活"。
- 模型若不支持某个表情会自动忽略，放心使用。

# 规则
- 起始姿态永远是自然垂手 (t=0 处 leftUpperArm Z=-70, rightUpperArm Z=+70)。
- 用到的骨骼必须在 t=0 和 t=duration 各有一个关键帧；非 loop 时首尾回到自然姿态。
- 关键帧足够密，动作才有缓急 (不是匀速)。
- duration 1.5~15 秒。根据内容定: 单发动作(点头、挥手) 2~4 秒；连续动作、舞蹈、表演 8~15 秒分段构成。
- 帧数随动作长度增加 (参考 每秒 2~4 个关键帧, 每骨最多 40 帧)。
- 角度不得超出关节活动范围，尤其:
  - leftHand/rightHand (手腕)、leftShoulder/rightShoulder (肩) 都不要动 (程序会自动生成自然手型和肩部跟随)。
  - 弯肘用 Z (或 Y) 为主轴。X 不要用 (会让前臂后甩破功)。
  - 颈+头合计 ±60 度内。spine/chest 各自 ±30 度内。
- 选一个主关节做主角，其余克制。不要全身一起大幅动。

# 人体解剖 (形准。节奏、次数、演法可自由设计)
- 挥手: 上臂抬到斜上 45~60 度 (rightUpperArm Z=-45~-60 / leftUpperArm Z=+45~+60)，
  肘弯 60~90 度把手放在"脸颊侧", 小臂左右往复 (前臂是挥的主役，不是整条胳膊)。
  真上举过头再挥很难看。
- 手抬到头的动作: upperArm 抬到脸侧高即可，再多就用"弯肘"而不是"抬上臂"。
- 膝盖 (lowerLeg) 是铰链关节: 只用 X 的 0~130 (向后弯)。负值(反关节)绝对禁止。Y/Z 几乎不用。
- 大腿 (upperLeg): X 负=向前抬(踢、迈步、走), X 正=向后。开腿、横步用 Z。
- 走路、踏步: 左右 upperLeg X 交替 (-30 ⇔ +10)，膝盖连动微屈，手臂逆相摆动，
  hips 随步伐小幅度上下。
- 踢: 蓄力 (upperLeg X +10 + 屈膝) → 快速蹬出 (upperLeg X -60~-90, 直膝) → 收回。
- 下蹲、落地、蓄力时 hips.p.y 一定下降, 否则脚会悬空: 轻蹲 -0.05~-0.1 / 深蹲 -0.2~-0.35。
- 转头回望: 视线(头)先行 → 身体(spine)跟随，有先后顺序。
- 往复运动(挥手、摇头)的折返点要减速: 在折返前放中间帧让它有缓急。

# 设计步骤
1. 先把"人类真实会怎么做这个动作"在脑子里过一遍：哪些关节、什么顺序、什么节奏、重心怎么移。
2. 再按上面的坐标约定数值化。不要套死模板。
3. 同一指令不要每次都编排成一样——动作要有个人风格、有变化。
        """.trimIndent()

    /** Second-pass self-correction prompt. */
    val REFINE_INSTRUCTION = """
        以下是按上面规则生成的骨骼动画 JSON。请你以动画师身份审阅，有问题就返回修正后的完整 JSON（没问题原样返回）。
        审查点:
        1. 活动范围：各关节是否越界? 手臂是否超过正上(±90)? 挥手类动作是否为「上臂微抬+弯肘+前臂往复」的正确形态?
        2. 轨迹: 手臂腿是否与身体/头交叉? 前臂没压住头吧(上臂60°以上+肘60°以上是禁止组合)? 左右手是否搞反?
        3. 自然度: 站立时肘是否完全伸直"? 躺/趴时肘膝是否支棱到空中(是否贴地)? 往复运动折返处是否放缓? 有没有预动与余韵?
        4. 完整性: 用到的骨骼是否有 t=0 和 t=duration 关键帧? 非 loop 首尾是否回到中立?
        5. 意图: 是否符合用户的指令?
        6. 表现: 是否太生硬机械? 作为人类动作是否自然?
        """.trimIndent()
}

/** A generic placeholder so the file has a stable entry even before the LLM
 *  client is wired in. The host supplies the actual transport (OpenAI/Claude/
 *  internal gateway) and calls [MotionLLMLike.generate]. */
interface MotionSpeaker {
    /** Generate a MotionSpec JSON string from [text]. Returns JSON text. */
    fun generate(text: String): String
}