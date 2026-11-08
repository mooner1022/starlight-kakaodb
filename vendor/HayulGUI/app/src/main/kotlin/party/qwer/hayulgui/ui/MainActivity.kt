package party.qwer.hayulgui.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import party.qwer.hayulgui.AppColors
import party.qwer.hayulgui.AppTypography
import party.qwer.hayulgui.HayulState
import party.qwer.hayulgui.core.Logx

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Logx.attach(applicationContext)
        HayulState.loadPrefs(applicationContext)
        HayulState.loadKey(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = AppColors.PrimaryAccent,
                    background = Color.Transparent,
                    surface = Color.White,
                    onBackground = AppColors.TextMain,
                    onSurface = AppColors.TextMain,
                    onSurfaceVariant = AppColors.TextSub,
                    outline = AppColors.GlassStroke,
                ),
                typography = AppTypography.app,
            ) {
                GlassBackground(Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}
