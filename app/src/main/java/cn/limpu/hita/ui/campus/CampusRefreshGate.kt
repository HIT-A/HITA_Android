package cn.limpu.hita.ui.campus

/** Owner-thread gate: one in-flight read per key, with ownership extending to its cache commit. */
internal class CampusRefreshGate<K> {
    class Request<K>(val key: K)

    private var active: Request<K>? = null

    fun begin(key: K): Request<K>? {
        if (active?.key == key) return null
        return Request(key).also { active = it }
    }

    fun owns(request: Request<K>, currentKey: K): Boolean = active === request && request.key == currentKey

    fun finish(request: Request<K>) {
        if (active === request) active = null
    }
}
