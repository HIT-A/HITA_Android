package cn.limpu.hita.utils

import android.os.Looper
import androidx.lifecycle.MutableLiveData

object LiveDataUtils {
    fun <T> getMutableLiveData(data: T? = null): MutableLiveData<T> {
        val res = MutableLiveData<T>()
        if (data != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                res.value = data
            } else {
                res.postValue(data)
            }
        }
        return res
    }
}
