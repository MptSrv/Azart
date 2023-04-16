package com.example.myapplication

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.dronevision.presentation.ui.bluetooth.BluetoothCallback
import com.example.dronevision.presentation.ui.bluetooth.BluetoothConnection
import com.example.myapplication.bluetooth.BluetoothActivityListener
import com.example.myapplication.bluetooth.BluetoothHandler
import com.example.myapplication.bluetooth.BluetoothHandlerImpl
import com.example.myapplication.bluetooth.BluetoothListItem
import com.example.myapplication.databinding.ActivityMainBinding
import com.example.myapplication.utils.PermissionTools
import com.example.nativelib.AzartBluetooth
import kotlinx.coroutines.*
import java.nio.charset.Charset

class MainActivity : AppCompatActivity(), BluetoothActivityListener,
    BluetoothHandler by BluetoothHandlerImpl() {

    private lateinit var binding: ActivityMainBinding
    private val azart = AzartBluetooth()
    private var connection: BluetoothConnection? = null
    private var dialog: SelectBluetoothFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        PermissionTools.checkAndRequestPermissions(this)

        connection = setupBluetooth(
            context = this,
            systemService = getSystemService(BLUETOOTH_SERVICE),
            listener = this
        )
        dialog = SelectBluetoothFragment(connection!!.getAdapter(), object : BluetoothCallback {
            override fun onClick(item: BluetoothListItem) {
                item.let {
                    connection!!.connect(it.mac)
                }
                dialog?.dismiss()
            }
        })

        binding.connectionButton.setOnClickListener {
            dialog?.show(supportFragmentManager, "ActionBottomDialog")
        }
        binding.button.setOnClickListener {
            /*
                Подготовка сообщения.
                Используется формат: 1) "+" ("плюс")
                                     2) номер р/ст получателя (например, здесь используется номер 2)
                                     3) ";" ("точка с запятой")
                                     4) Текст короткого сообщения (SDS)
                                     5) "\r\n" (CR + LF - Символы возврата каретки и новой строки)
                                     6) Полученную строку преобразовываем в массив байт в соответствии с ISO-8859-5
             */
            val wrappedMessage = "+2;Привет Azart!\r\n"
            val iso88595Data: ByteArray = wrappedMessage.toByteArray(charset("ISO-8859-5"))

            sendData(iso88595Data)
        }

        startNative()
    }

    private fun startNative() {
        val isRunning = true
        GlobalScope.launch(Dispatchers.IO){
            while (isRunning) {
                val controlData: ByteArray =
                    azart.readBytes()// считывает данные для отправки на р/cт
                if (controlData.isNotEmpty()) {
                    writeControl(controlData)
                }
                try {
                    delay(1)
                } catch (e: InterruptedException) {
                    throw java.lang.RuntimeException(e)
                }
            }
        }

        GlobalScope.launch(Dispatchers.IO){
            while (isRunning) {
                val bytes: ByteArray? =
                    azart.readStringAsBytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    manageReceivedData(bytes);
                    // здесь мы получаем данные SDS от р/ст
                }
                try {
                    delay(100)
                } catch (e: InterruptedException) {
                    throw java.lang.RuntimeException(e)
                }
            }
        }

        /*
            Надо убедиться, что установлено подключение к р/ст (а не просто к Bluetooth-Модулю)
            Для этого можно периодически опрашивать номер подключаемой р/ст, и как только он будет больше нуля - соединение установлено
         */
        GlobalScope.launch(Dispatchers.IO){
            while (isRunning) {
                val selfNumber = azart.getSelfNumber()

                if (selfNumber > 0) {
                    Log.i(
                        "Azart",
                        "Соединение с радиостанцией установлено. Номер р/ст: " + selfNumber
                    )
                    showMessage("Есть контакт; можно начинать отправлять и получать сообщения.")
                    break // достаточно дождаться соединения с р/ст и можно этот поток останавливать
                }

                try {
                    delay(10)
                } catch (e: InterruptedException) {
                    throw java.lang.RuntimeException(e)
                }
            }
        }
    }

    override fun showMessage(str: String) {
        runOnUiThread {
            Toast.makeText(applicationContext, str, Toast.LENGTH_SHORT).show()
        }
    }

    override fun receiveBytes(bytes: ByteArray) {
        azart.writeBytes(bytes) // отправляем данные, полученные от bluetooth в native-lib
    }

    private fun manageReceivedData(bytes: ByteArray) {
        //val message = String(bytes)
        // И обратно - при получении преобразуем байты в соответствии с форматом ISO-8859-5
        val message = String(bytes, 0, bytes.size, Charset.forName("ISO-8859-5"))

        /*
            Надо учесть ещё такой момент: если SDS достаточно длинное или соединение медленное,
            то приходить байты могут не сразу, а как-бы по частям (ориентироваться надо на конечный символ '\n').
            Поэтому, может потребоваться промежуточный контейнер, куда мы складируем байты,
            периодически проверяя, не готово ли сообщение.

            Что-то вроде этого:

            private void manageReceivedData(byte[] data) throws UnsupportedEncodingException {
                parser187.append(data, data.length);    <- НАКАПЛИВАЕМ ДАННЫЕ
                if (parser187.isComplete()) {           <- ПРОВЕРЯЕМ ГОТОВНОСТЬ (накопленный массив заканчивается на символ '\n')
                    String receivedMessage = parser187.getMessage();
                    int radioNumber = Parser187.getSenderNumber(receivedMessage);
                    ...
         */

        showMessage(message)
    }

    private fun writeControl(data: ByteArray) {
        // отправка данных в bluetooth или usb
        connection?.sendMessage(data)
    }

     fun sendData(data: ByteArray) {
        // В отдельном потоке (функция блокирующая, чтобы не подвешивать GUI) отправляем данные в native-lib (writeNative)
         runBlocking {
             launch{
                 if (data.isNotEmpty())
                     azart.writeString(data)
             }
         }
        // а в постоянном цикле ведется опрос native-lib (readNativeControl) и, если готовы данные, они будут переданы далее (на usb/bluetooth) через writeControl
    }
}