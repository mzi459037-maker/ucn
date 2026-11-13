package com.example.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object DeviceContactsReader {

    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun readContacts(context: Context): List<DeviceContactCandidate> {
        if (!hasContactsPermission(context)) {
            throw SecurityException("READ_CONTACTS permission is required to sync device contacts.")
        }

        val resolver = context.contentResolver
        val byContactId = linkedMapOf<Long, MutableCandidate>()

        // 1. Read contact email addresses
        resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY)
            val addrIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (cursor.moveToNext()) {
                val contactId = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""
                val email = if (addrIdx >= 0) cursor.getString(addrIdx).orEmpty().trim() else ""
                if (email.isBlank()) continue
                val entry = byContactId.getOrPut(contactId) {
                    MutableCandidate(displayName = name.ifBlank { email.substringBefore("@") })
                }
                if (entry.displayName.isBlank() && name.isNotBlank()) {
                    entry.displayName = name
                }
                entry.emails.add(email)
            }
        }

        // 2. Read contact phone numbers
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val normIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
            while (cursor.moveToNext()) {
                val contactId = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""
                val number = if (numIdx >= 0) cursor.getString(numIdx).orEmpty().trim() else ""
                val normalized = if (normIdx >= 0) cursor.getString(normIdx).orEmpty().trim() else ""
                val chosen = normalized.ifBlank { number }
                if (chosen.isBlank()) continue
                val entry = byContactId.getOrPut(contactId) {
                    MutableCandidate(displayName = name.ifBlank { chosen })
                }
                if (entry.displayName.isBlank() && name.isNotBlank()) {
                    entry.displayName = name
                }
                if (number.isNotBlank()) entry.phones.add(number)
                if (normalized.isNotBlank()) entry.phones.add(normalized)
            }
        }

        return byContactId.values.map {
            DeviceContactCandidate(
                displayName = it.displayName.ifBlank { "Contact" }.take(120),
                emails = it.emails.toList(),
                phones = it.phones.toList()
            )
        }
    }

    private class MutableCandidate(
        var displayName: String,
        val emails: LinkedHashSet<String> = linkedSetOf(),
        val phones: LinkedHashSet<String> = linkedSetOf()
    )
}
