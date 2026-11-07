package com.x10pult.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.x10pult.app.ui.App
import com.x10pult.app.ui.X10Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            X10Theme {
                val vm: AppViewModel = viewModel()
                App(vm)
            }
        }
    }
}
