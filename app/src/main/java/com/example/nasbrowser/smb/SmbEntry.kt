package com.example.nasbrowser.smb

import android.os.Parcel
import android.os.Parcelable

data class SmbEntry(
    val name: String,
    val path: String,          // path within the share, e.g. "Photos/IMG_001.jpg"
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readByte() != 0.toByte(),
        parcel.readLong(),
        parcel.readLong()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeString(path)
        parcel.writeByte(if (isDirectory) 1 else 0)
        parcel.writeLong(size)
        parcel.writeLong(lastModified)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SmbEntry> {
        override fun createFromParcel(parcel: Parcel): SmbEntry = SmbEntry(parcel)
        override fun newArray(size: Int): Array<SmbEntry?> = arrayOfNulls(size)
    }
}
