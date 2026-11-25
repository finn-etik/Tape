package app.tape

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.tape.ui.TapeRoot
import app.tape.ui.theme.TapeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TapeTheme { TapeRoot() }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.player.connect()
    }

    override fun onStop() {
        graph.player.disconnect()
        super.onStop()
    }
}
