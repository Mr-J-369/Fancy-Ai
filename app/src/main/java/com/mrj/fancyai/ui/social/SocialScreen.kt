package com.mrj.fancyai.ui.social

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.mrj.fancyai.data.db.entity.SocialPostEntity

@Composable
fun SocialScreen(
    platform: String,
    navController: NavHostController,
    viewModel: SocialViewModel
) {
    val posts by viewModel.posts.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        Text(
            platform.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(posts) { post ->
                SocialPostCard(
                    post = post,
                    platform = platform,
                    onDelete = { viewModel.deletePost(post.id) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun SocialPostCard(
    post: SocialPostEntity,
    platform: String,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    when (platform) {
                        "ustagram" -> {
                            Text(
                                text = post.caption ?: "No caption",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (post.imageRef != null) {
                                Text(
                                    text = "📸 Image attached",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        "rebbit" -> {
                            Text(
                                text = post.title ?: "Untitled",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = post.text ?: "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        "y" -> {
                            Text(
                                text = post.text ?: "Empty post",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = java.text.SimpleDateFormat("MMM d, HH:mm").format(post.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
