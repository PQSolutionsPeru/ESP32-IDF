package com.pqsolutions.hdd_monitor.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pqsolutions.hdd_monitor.data.UserData

@Composable
fun UserList(
    users: List<UserData>,
    onEditClick: (UserData) -> Unit,
    onDeleteClick: (UserData) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = users,
            key = { it.documentName }
        ) { user ->
            UserCard(
                user = user,
                onEditClick = { onEditClick(user) },
                onDeleteClick = { onDeleteClick(user) }
            )
        }
    }
}