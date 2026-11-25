package cn.limpu.hita.ui.campus

import cn.limpu.hita.data.model.campus.SilentPodContact
import cn.limpu.hita.data.model.campus.SilentPodReservation
import cn.limpu.hita.data.model.campus.SilentPodSpace

internal sealed interface SilentPodWrite {
    data class Reserve(
        val space: SilentPodSpace,
        val date: String,
        val start: String,
        val end: String,
        val phone: String,
        val contactIds: List<Int>,
        val previousReservationIds: Set<Int>,
    ) : SilentPodWrite {
        fun foundIn(records: List<SilentPodReservation>): Boolean = records.any {
            it.isMain && it.id !in previousReservationIds && it.spaceName == space.codeName &&
                it.areaName == space.areaFullName && it.date == date &&
                it.startTime == start && it.endTime == end
        }
    }

    data class Cancel(val reservation: SilentPodReservation, val reason: String) : SilentPodWrite {
        fun foundIn(records: List<SilentPodReservation>): Boolean = records.any {
            it.id == reservation.id && it.isMain && it.status == "CANCELED"
        }
    }

    data class AddContact(val name: String, val cardNo: String) : SilentPodWrite {
        fun findIn(contacts: List<SilentPodContact>): SilentPodContact? = contacts.firstOrNull {
            it.name.equals(name, ignoreCase = true) && it.cardNo == cardNo
        }
    }

    data class RemoveContact(val contact: SilentPodContact) : SilentPodWrite
}

/** Keep the exact submitted target until a response or a positive remote record resolves it. */
internal class SilentPodWriteGate {
    sealed interface State {
        data object Idle : State
        data class Submitting(val write: SilentPodWrite) : State
        data class Unconfirmed(val write: SilentPodWrite, val checking: Boolean) : State
        data class Confirmed(val write: SilentPodWrite) : State
    }

    var state: State = State.Idle
        private set

    fun begin(write: SilentPodWrite): Boolean {
        if (state is State.Submitting || state is State.Unconfirmed) return false
        state = State.Submitting(write)
        return true
    }

    fun reject() { state = State.Idle }

    fun confirm(write: SilentPodWrite) { state = State.Confirmed(write) }

    fun unknown(write: SilentPodWrite) { state = State.Unconfirmed(write, checking = false) }

    fun beginCheck(): SilentPodWrite? {
        val current = state as? State.Unconfirmed ?: return null
        if (current.checking) return null
        state = current.copy(checking = true)
        return current.write
    }
}
