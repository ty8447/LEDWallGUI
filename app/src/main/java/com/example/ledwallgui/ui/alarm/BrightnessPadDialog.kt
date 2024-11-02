import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import com.example.ledwallgui.R

class BrightnessPadDialog(context: Context, private val initialValue: Int, private val callback: (Int) -> Unit) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.brightness_number_pad)

        val numberButtons = listOf(
            findViewById<Button>(R.id.number_1),
            findViewById<Button>(R.id.number_2),
            findViewById<Button>(R.id.number_3),
            findViewById<Button>(R.id.number_4),
            findViewById<Button>(R.id.number_5),
            findViewById<Button>(R.id.number_6),
            findViewById<Button>(R.id.number_7),
            findViewById<Button>(R.id.number_8),
            findViewById<Button>(R.id.number_9),
            findViewById<Button>(R.id.number_0)
        )

        val inputStringBuilder = StringBuilder(initialValue.toString())
        val enterButton = findViewById<Button>(R.id.enter)

        numberButtons.forEach { button ->
            button.setOnClickListener {
                val buttonText = (button as Button).text
                if (inputStringBuilder.length < 3) {
                    inputStringBuilder.append(buttonText)
                }
                updateInputText(inputStringBuilder.toString())
            }
        }

        val backspaceButton = findViewById<Button>(R.id.number_backspace)
        backspaceButton.setOnClickListener {
            if (inputStringBuilder.isNotEmpty()) {
                inputStringBuilder.deleteCharAt(inputStringBuilder.length - 1)
                updateInputText(inputStringBuilder.toString())
            }
        }

        enterButton.setOnClickListener {
            val enteredValue = inputStringBuilder.toString().toIntOrNull() ?: 0
            val maximumValue = 100

            val finalValue = if (enteredValue > maximumValue) {
                maximumValue
            } else {
                enteredValue
            }

            callback.invoke(finalValue)
            cancel()
        }

    }

    private fun updateInputText(text: String) {
        callback.invoke(text.toIntOrNull() ?: 0)
    }
}
