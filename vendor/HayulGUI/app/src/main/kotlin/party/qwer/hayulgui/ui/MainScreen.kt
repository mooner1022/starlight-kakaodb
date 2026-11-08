package party.qwer.hayulgui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import party.qwer.hayulgui.AppColors

private val tabs = listOf("홈", "앱목록", "설치")
private val tabIcons = listOf(
    Icons.Default.Home, Icons.Default.Input, Icons.Default.InstallMobile,
)

@Composable
fun MainScreen() {
    var selected by remember { mutableIntStateOf(0) }
    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = AppColors.BottomNavBg,
                contentColor = AppColors.TextSub,
                tonalElevation = 0.dp,
            ) {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tabIcons[index], contentDescription = title) },
                        label = { Text(title, fontWeight = FontWeight.Medium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppColors.PrimaryAccent,
                            selectedTextColor = AppColors.PrimaryAccent,
                            indicatorColor = AppColors.PrimaryAccent.copy(alpha = 0.13f),
                            unselectedIconColor = AppColors.TextSub,
                            unselectedTextColor = AppColors.TextSub,
                        ),
                    )
                }
            }
        },
    ) { insets ->
        val content = Modifier.fillMaxSize().padding(insets)
        when (selected) {
            0 -> HomeScreen(content)
            1 -> AppListScreen(content)
            2 -> InstallGuideScreen(content)
        }
    }
}
