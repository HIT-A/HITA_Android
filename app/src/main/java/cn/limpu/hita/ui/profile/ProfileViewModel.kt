package cn.limpu.hita.ui.profile

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import com.limpu.component.data.DataState
import com.limpu.component.data.StringTrigger
import com.limpu.hitauser.data.repository.LocalUserRepository
import com.limpu.hitauser.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val localUserRepository: LocalUserRepository
) : ViewModel() {

    private var profileController = MutableLiveData<StringTrigger>()

    var userProfileLiveData = profileController.switchMap { input ->
        val user = localUserRepository.getLoggedInUser()
        if (user.isValid()) {
            return@switchMap repository.getUserProfile(input.data, user.token!!)
        } else {
            return@switchMap MutableLiveData(DataState(DataState.STATE.NOT_LOGGED_IN))
        }
    }

    fun startRefresh(id: String) {
        profileController.value = StringTrigger.getActioning(id)
    }

    fun isCurrentUser(id: String?): Boolean {
        return id == localUserRepository.getLoggedInUser().id
    }
}
