package com.secntfy.androidtest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.secntfy.android.SecNtfy
import com.secntfy.androidtest.ui.theme.SecNtfyAndroidTestTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SecNtfyAndroidTestTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Greeting("Android")
                }
            }
        }

        val secntfy = SecNtfy.getInstance()
        //secntfy.initialize("https://api.secntfy.app", ctx = baseContext)
        secntfy.initialize("http://192.168.178.80", ctx = baseContext)
        secntfy.configure("NTFY-APP-587VXchT0NPQzCulKR17PbTssr2bueBBWY8jsqvGhXhmnA")
        secntfy.setFCMToken("test1234TokenFCM")
        GlobalScope.launch(Dispatchers.IO) {
            secntfy.getNtfyToken { ntfyToken, error ->
                if (error != null) {
                    println(error.localizedMessage)
                    return@getNtfyToken
                }
                println("NTFY-Token: $ntfyToken")
                val encTest =
                    "IznujsvKab50eWrlEQfpMW23QLkzTGTE4gwBQbRaitfX704w0TpCdvTgRNvKCKLbR57wtG7j8ESIkybW7TgrLi19pE/FRziJFpqVZrz7uc3e2fZg21wC9cQF0/ySvjNKWJsAwt+GIuOrNFeHbHD5Xq6bPLX9nk+HHN92FUl8hhsL382UGHKjUSa1z5SAASqX6JEHYJ8SDJK/iwaqX2VaZ15kiWyirV25g5ecICLHrIXVYZ2X9fTeM64yd8Ompm/eT9QW7Ac6wIbB9olcii0h1Ynul4o/dtD99e3+Mq6l9WAwEcnpRDiAy7ew04GAMM9NfXSgEIgIyVqzG9TYYsgL9w=="
                secntfy.DecryptMessage(encTest)
            }
            println("Running in the background thread: ${Thread.currentThread().name}")
            secntfy.MessageReceived("ThCBp8B4z41rn6oF")
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SecNtfyAndroidTestTheme {
        Greeting("Android")
    }
}