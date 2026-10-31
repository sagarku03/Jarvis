package com.example.domain.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.ContactCandidate
import com.example.domain.repository.MemoryRepository

/**
 * Resolves spoken or typed contact names (including Indian kinship aliases like "Mummy", "Papa", "Bhai")
 * against both local MemoryFacts and Android ContactsContract.
 * Never fabricates a phone number when a contact is missing.
 */
@Singleton
class ContactsResolver @Inject constructor(
    private val context: Context,
    private val memoryRepository: MemoryRepository
) {
    private val kinshipSynonyms: Map<String, List<String>> = mapOf(
        "mom" to listOf("mom", "mummy", "maa", "mother", "ammi", "aai"),
        "dad" to listOf("dad", "papa", "father", "pitaji", "abba", "baba"),
        "brother" to listOf("brother", "bhai", "bhaiya"),
        "sister" to listOf("sister", "didi", "behen"),
        "wife" to listOf("wife", "biwi"),
        "husband" to listOf("husband", "pati")
    )

    suspend fun resolveCandidates(queryNameOrNumber: String): List<ContactCandidate> {
        val trimmed = queryNameOrNumber.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Direct phone number check
        val digitsOnly = trimmed.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length >= 10 && digitsOnly.count { it.isDigit() } >= 10) {
            return listOf(
                ContactCandidate(
                    displayName = trimmed,
                    phoneNumber = digitsOnly,
                    maskedNumber = maskPhoneNumber(digitsOnly)
                )
            )
        }

        val expandedQueries = expandAliases(trimmed)
        val candidates = mutableListOf<ContactCandidate>()

        // 1. Check user's saved Memory facts first (e.g. "Mom" -> "+919876543210")
        val memories = memoryRepository.getAllMemoriesSnapshot()
        for (fact in memories) {
            val keyLower = fact.key.lowercase()
            if (expandedQueries.any { alias -> keyLower.contains(alias) }) {
                val extractedPhone = fact.value.filter { it.isDigit() || it == '+' }
                if (extractedPhone.count { it.isDigit() } >= 10) {
                    candidates.add(
                        ContactCandidate(
                            displayName = fact.key,
                            phoneNumber = extractedPhone,
                            maskedNumber = maskPhoneNumber(extractedPhone)
                        )
                    )
                }
            }
        }

        // 2. Query Android ContactsContract if READ_CONTACTS permission is granted
        val hasContactsPermission = runCatching {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (hasContactsPermission) {
            runCatching {
                val resolver = context.contentResolver
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
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
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                        val rawNum = if (numIdx >= 0) cursor.getString(numIdx).orEmpty() else ""
                        val normalizedNum = rawNum.filter { it.isDigit() || it == '+' }
                        if (name.isNotBlank() && normalizedNum.length >= 8) {
                            val nameLower = name.lowercase()
                            if (expandedQueries.any { alias -> nameLower.contains(alias) }) {
                                if (candidates.none { it.phoneNumber.takeLast(10) == normalizedNum.takeLast(10) }) {
                                    candidates.add(
                                        ContactCandidate(
                                            displayName = name,
                                            phoneNumber = normalizedNum,
                                            maskedNumber = maskPhoneNumber(normalizedNum)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        return candidates
    }

    private fun expandAliases(input: String): List<String> {
        val lower = input.lowercase().trim()
        val matchingGroup = kinshipSynonyms.values.firstOrNull { group ->
            group.any { it.equals(lower, ignoreCase = true) }
        }
        return matchingGroup ?: listOf(lower)
    }

    companion object {
        fun maskPhoneNumber(phone: String): String {
            val clean = phone.trim()
            if (clean.length <= 4) return clean
            val last4 = clean.takeLast(4)
            return "•••• ••$last4"
        }
    }
}
