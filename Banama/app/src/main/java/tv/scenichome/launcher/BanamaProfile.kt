package tv.scenichome.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

private val avatarColors = listOf(Color(0xFF6C5DE0), Color(0xFF308176), Color(0xFFAC536C), Color(0xFF496FBE))
private val gold = Color(0xFFFFD58C)
private val primaryButton = Color(0xFF655095)
private val secondaryButton = Color(0xFF332C42)
private val signOutButton = Color(0xFF542D3A)

@Composable
internal fun ProfileBadge(account: MediaAccount?, modifier: Modifier = Modifier) {
    val tint = avatarColors[(account?.username.orEmpty().hashCode()).mod(avatarColors.size)]
    Box(
        modifier.background(
            Brush.linearGradient(listOf(tint.copy(alpha = .48f), tint.copy(alpha = .20f))),
            CircleShape,
        ).border(1.dp, Color.White.copy(alpha = .27f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val initials = account?.initials.orEmpty()
        if (initials.isEmpty()) {
            Image(painterResource(R.drawable.media_profile), null, Modifier.fillMaxSize(.48f))
        } else {
            Text(initials, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun ProfileDialog(
    account: MediaAccount?,
    connected: Boolean,
    close: () -> Unit,
    connect: () -> Unit,
    disconnect: () -> Unit,
) {
    var focusedAction by remember { mutableStateOf<String?>(null) }
    val focus = FocusMove(if (connected) "profile/close" else "profile/connect", 0)
    val focused: (String) -> Unit = { focusedAction = it }
    val shape = RoundedCornerShape(22.dp)

    BanamaDialog(close) {
        Column(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 490.dp).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF272438), Color(0xFF18171F))), shape)
                .border(1.dp, Color(0xFF49435A), shape)
                .padding(24.dp).guardDialogSelection { focusedAction },
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileBadge(account, Modifier.size(54.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(account?.username?.ifBlank { "Profile" } ?: "Profile",
                        fontSize = 25.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (connected) "30nama account" else "Guest", color = Color(0xFFD4CDE4), fontSize = 15.sp)
                }
            }
            if (connected) {
                val subscription = RoundedCornerShape(14.dp)
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF302A3A), subscription)
                        .border(1.dp, Color(0xFF53475F), subscription).padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Subscription", color = Color(0xFFD4CDE4), fontSize = 14.sp)
                        Text(account?.tier ?: "Unavailable", color = if (account?.type == "golden") gold else foreground,
                            fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(account?.days?.toString() ?: "—", fontSize = 29.sp,
                            lineHeight = 33.sp, fontWeight = FontWeight.SemiBold)
                        Text("Days remaining", color = Color(0xFFD4CDE4), fontSize = 14.sp)
                    }
                }
            } else {
                Text("Connect to see your subscription and resume watching.", color = Color(0xFFD4CDE4), fontSize = 17.sp)
            }
            Row(
                Modifier.fillMaxWidth().height(54.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val button = Modifier.weight(1f).fillMaxHeight()
                if (connected) {
                    ProfileAction("Close", "profile/close", close, focus, focused, primaryButton, button)
                    ProfileAction("Sign out", "profile/disconnect", disconnect, focus, focused, signOutButton, button)
                } else {
                    ProfileAction("Connect 30nama", "profile/connect", connect, focus, focused, primaryButton, button)
                    ProfileAction("Close", "profile/close", close, focus, focused, secondaryButton, button)
                }
            }
        }
    }
}

@Composable
private fun ProfileAction(
    label: String,
    id: String,
    onClick: () -> Unit,
    move: FocusMove,
    onFocused: (String) -> Unit,
    fill: Color,
    modifier: Modifier,
) {
    FocusSurface(id, move, onFocused, onClick, modifier, fill = fill) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, Modifier.padding(horizontal = 12.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
