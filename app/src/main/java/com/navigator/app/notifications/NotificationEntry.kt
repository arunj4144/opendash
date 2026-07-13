package com.navigator.app.notifications

data class NotificationEntry(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTimeMs: Long,
    val isNavigation: Boolean
)
