package com.example.logic

import com.example.data.model.Subscription
import com.example.data.model.User
import com.example.data.model.UserRole
import com.example.data.repository.DatabaseRepository
import kotlin.math.ceil

// Subscription gate shared logic. Mirrors the web app (firebase.ts):
// strict mode — no doc, expired, inactive, or TRIAL plan => blocked.
// Only a super-admin recharge (30D/90D/365D/custom) unlocks login.
object SubscriptionGate {

    // Android has no SUPER_ADMIN role; super admin is mobile "admin".
    fun isExempt(user: User): Boolean = user.mobile == "admin"

    fun resolveOwner(user: User): String? {
        if (isExempt(user)) return null
        return if (user.role == UserRole.ADMIN) user.mobile
        else user.creatorMobile.ifEmpty { user.mobile }
    }

    data class Check(val ok: Boolean, val subscription: Subscription?)

    suspend fun check(repository: DatabaseRepository, user: User): Check {
        val owner = resolveOwner(user) ?: return Check(true, null)
        return try {
            val sub = repository.getSubscription(owner)
            val now = System.currentTimeMillis()
            if (sub == null || sub.status != "ACTIVE" || sub.expiresAt <= now || sub.planName.startsWith("TRIAL_")) {
                Check(false, sub)
            } else {
                Check(true, sub)
            }
        } catch (e: Exception) {
            // Offline / unreachable: fail-open so field work doesn't stop.
            e.printStackTrace()
            Check(true, null)
        }
    }

    fun daysLeft(sub: Subscription): Int =
        ceil((sub.expiresAt - System.currentTimeMillis()) / 86400000.0).toInt()

    enum class State { ACTIVE, EXPIRING, BLOCKED, NONE }

    fun state(sub: Subscription?): State {
        if (sub == null) return State.NONE
        if (sub.status != "ACTIVE" || sub.expiresAt <= System.currentTimeMillis() || sub.planName.startsWith("TRIAL_")) {
            return State.BLOCKED
        }
        return if (daysLeft(sub) <= 7) State.EXPIRING else State.ACTIVE
    }
}
