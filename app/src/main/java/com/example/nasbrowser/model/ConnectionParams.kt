package com.example.nasbrowser.model

import android.os.Parcel
import android.os.Parcelable

data class ConnectionParams(
    val host: String,
    val username: String,
    val password: String,
    val domain: String
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(host)
        parcel.writeString(username)
        parcel.writeString(password)
        parcel.writeString(domain)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ConnectionParams> {
        override fun createFromParcel(parcel: Parcel): ConnectionParams = ConnectionParams(parcel)
        override fun newArray(size: Int): Array<ConnectionParams?> = arrayOfNulls(size)
    }
}
