package com.riffle.core.data.localfiles

interface FolderPickerInterface {
    fun pickFolder(onResult: (FolderUri?) -> Unit)
}
