package com.example.domain.contact

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.repository.MemoryRepository

data class ResolvedContact(
    val displayName: String,
    val phoneNumber: String,
    val source: String = "Contacts"
) {
    val maskedNumber: String
        get() = ContactsResolver.maskPhoneNumber(phoneNumber)
}

sealed class ContactResolutionResult {
    data class Resolved(val contact: ResolvedContact) : ContactResolutionResult()
    data class Ambiguous(
        val query: String,
        val candidates: List<ResolvedContact>
    ) : ContactResolutionResult() {
        fun messageEn(): String {
            val options = candidates.joinToString(", ") { "${it.displayName} (${it.maskedNumber})" }
            return "Multiple contacts matched '$query': $options. Please specify which one."
        }

        fun messageHi(): String {
            val options = candidates.joinToString(", ") { "${it.displayName} (${it.maskedNumber})" }
            return "'$query' नाम से कई संपर्क मिले: $options। कृपया स्पष्ट करें कि किसे चुनना है।"
        }
    }

    data class NotFound(
        val query: String,
        val permissionMissing: Boolean = false,
        val messageEn: String,
        val messageHi: String
    ) : ContactResolutionResult()
}

interface ContactsDataSource {
    fun hasReadContactsPermission(): Boolean
    fun querySystemContacts(): List<ResolvedContact>
}

class AndroidContactsDataSource(
    private val context: Context
) : ContactsDataSource {

    override fun hasReadContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun querySystemContacts(): List<ResolvedContact> {
        if (!hasReadContactsPermission()) return emptyList()
        val results = mutableListOf<ResolvedContact>()
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        runCatching {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val rawNumber = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    val normalizedNumber = ContactsResolver.normalizePhoneDigits(rawNumber)
                    if (name.isNotEmpty() && normalizedNumber.isNotEmpty()) {
                        results.add(
                            ResolvedContact(
                                displayName = name,
                                phoneNumber = normalizedNumber,
                                source = "Contacts"
                            )
                        )
                    }
                }
            }
        }
        return results.distinctBy { "${it.displayName.lowercase()}_${it.phoneNumber.takeLast(10)}" }
    }
}

@Singleton
class ContactsResolver @Inject constructor(
    private val dataSource: ContactsDataSource,
    private val memoryRepository: MemoryRepository? = null
) {

    companion object {
        private val EXPLICIT_NUMBER_REGEX = Regex("^\\+?[0-9][0-9\\s\\-()]{2,16}$")
        private val PHONE_EXTRACTION_REGEX = Regex("(\\+?\\d[\\d\\s\\-]{6,15}\\d)")

        fun isExplicitPhoneNumber(rawInput: String): Boolean {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return false
            if (!EXPLICIT_NUMBER_REGEX.matches(trimmed)) return false
            val digitCount = trimmed.count { it.isDigit() }
            return digitCount in 3..15
        }

        fun normalizePhoneDigits(rawPhone: String): String {
            val trimmed = rawPhone.trim()
            val hasPlus = trimmed.startsWith("+")
            val digits = trimmed.filter { it.isDigit() }
            if (digits.isEmpty()) return ""
            return if (hasPlus) "+$digits" else digits
        }

        /**
         * Masks a phone number for SafetyGate UI display, e.g.:
         * "+919876543210" -> "+91 98••• ••210"
         * "9876543210"    -> "98••• ••210"
         */
        fun maskPhoneNumber(rawPhone: String): String {
            val normalized = normalizePhoneDigits(rawPhone)
            val digitsOnly = normalized.removePrefix("+")
            if (digitsOnly.length < 6) return normalized

            return if (normalized.startsWith("+") && digitsOnly.length > 10) {
                val countryCode = digitsOnly.dropLast(10)
                val local10 = digitsOnly.takeLast(10)
                val prefix2 = local10.take(2)
                val suffix3 = local10.takeLast(3)
                "+$countryCode ${prefix2}••• ••$suffix3"
            } else if (digitsOnly.length >= 10) {
                val local10 = digitsOnly.takeLast(10)
                val prefix2 = local10.take(2)
                val suffix3 = local10.takeLast(3)
                "${prefix2}••• ••$suffix3"
            } else {
                val prefix = digitsOnly.take(2)
                val suffix = digitsOnly.takeLast(2)
                "${prefix}•••$suffix"
            }
        }
    }

    suspend fun resolve(rawQuery: String): ContactResolutionResult {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            return ContactResolutionResult.NotFound(
                query = rawQuery,
                permissionMissing = false,
                messageEn = "No contact name or phone number was provided.",
                messageHi = "कोई संपर्क नाम या फ़ोन नंबर नहीं दिया गया।"
            )
        }

        // 1. Check if the user explicitly spoke/typed a phone number (only digits/+/spaces/hyphens)
        if (isExplicitPhoneNumber(query)) {
            val normalized = normalizePhoneDigits(query)
            return ContactResolutionResult.Resolved(
                ResolvedContact(
                    displayName = "Spoken Number",
                    phoneNumber = normalized,
                    source = "ExplicitUserDigits"
                )
            )
        }

        val queryLower = query.lowercase()
        val matchedContacts = mutableListOf<ResolvedContact>()

        // 2. Check MemoryRepository for user-approved Contact Nicknames
        val memories = runCatching { memoryRepository?.getAllMemoriesSnapshot().orEmpty() }.getOrDefault(emptyList())
        for (mem in memories) {
            val isContactMemory = mem.category.contains("contact", ignoreCase = true) ||
                mem.category.contains("nickname", ignoreCase = true) ||
                mem.keyLabel.lowercase().contains(queryLower)
            if (isContactMemory) {
                val keyLower = mem.keyLabel.lowercase()
                val valLower = mem.contentValue.lowercase()
                if (keyLower == queryLower ||
                    keyLower.contains(queryLower) ||
                    queryLower.contains(keyLower) ||
                    valLower.contains(queryLower)
                ) {
                    val phoneMatch = PHONE_EXTRACTION_REGEX.find(mem.contentValue)?.value
                        ?: PHONE_EXTRACTION_REGEX.find(mem.keyLabel)?.value
                    val normalized = phoneMatch?.let { normalizePhoneDigits(it) }.orEmpty()
                    if (normalized.length >= 3) {
                        matchedContacts.add(
                            ResolvedContact(
                                displayName = mem.keyLabel,
                                phoneNumber = normalized,
                                source = "MemoryNickname"
                            )
                        )
                    }
                }
            }
        }

        // 3. Query Android ContactsContract (exact match first, then token/fuzzy match)
        val hasPerm = dataSource.hasReadContactsPermission()
        if (hasPerm) {
            val systemContacts = dataSource.querySystemContacts()
            val exactMatches = systemContacts.filter {
                it.displayName.equals(query, ignoreCase = true)
            }
            if (exactMatches.isNotEmpty()) {
                matchedContacts.addAll(exactMatches)
            } else {
                val fuzzyMatches = systemContacts.filter { contact ->
                    val nameLower = contact.displayName.lowercase()
                    nameLower.contains(queryLower) ||
                        queryLower.contains(nameLower) ||
                        levenshteinDistance(nameLower, queryLower) <= 1
                }
                matchedContacts.addAll(fuzzyMatches)
            }
        }

        val deduplicated = matchedContacts.distinctBy {
            it.phoneNumber.takeLast(10)
        }

        return when {
            deduplicated.size == 1 -> ContactResolutionResult.Resolved(deduplicated.first())
            deduplicated.size > 1 -> ContactResolutionResult.Ambiguous(query, deduplicated.take(5))
            !hasPerm -> ContactResolutionResult.NotFound(
                query = query,
                permissionMissing = true,
                messageEn = "Contact '$query' not found because Contacts permission (READ_CONTACTS) is not granted. Open Permissions to grant access.",
                messageHi = "संपर्क अनुमति (READ_CONTACTS) न होने के कारण '$query' नहीं मिला। कृपया अनुमति दें।"
            )
            else -> ContactResolutionResult.NotFound(
                query = query,
                permissionMissing = false,
                messageEn = "Contact '$query' was not found in your Contacts or saved nicknames.",
                messageHi = "'$query' आपके संपर्कों या सहेजे गए उपनामों में नहीं मिला।"
            )
        }
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        if (lhs == rhs) return 0
        if (lhs.isEmpty()) return rhs.length
        if (rhs.isEmpty()) return lhs.length

        val lhsLen = lhs.length + 1
        val rhsLen = rhs.length + 1
        var cost = IntArray(lhsLen) { it }
        var newCost = IntArray(lhsLen)

        for (i in 1 until rhsLen) {
            newCost[0] = i
            for (j in 1 until lhsLen) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLen - 1]
    }
}
