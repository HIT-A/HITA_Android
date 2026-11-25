package cn.limpu.hita.ui.design

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * 字符串集合的可保存状态：按稳定 ID 记住"哪些行被展开"，
 * 界面重建、旋转和滚动复用后仍然成立。
 *
 * 集合本身不可直接写入 Bundle，这里用 ArrayList 承载；
 * 不在集合中的 ID 回落到该行的默认状态。
 */
val StringSetSaver: Saver<Set<String>, Any> = listSaver(
    save = { ArrayList(it) },
    restore = { it.toSet() },
)
