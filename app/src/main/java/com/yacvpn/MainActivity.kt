package com.yacvpn

import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.yacvpn.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startTunnel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("config", MODE_PRIVATE)

        binding.etGateway.setText(
            prefs.getString("gateway", "wss://d5d39r7l30a40c2n6joc.uvah0e6r.apigw.yandexcloud.net/_helper")
        )
        binding.etToken.setText(
            prefs.getString("token", "3f4hjfjn")
        )

        binding.btnConnect.setOnClickListener {
            try {
            saveConfig()
            val prepare = VpnService.prepare(this@MainActivity)
            if (prepare != null) {
                vpnLauncher.launch(prepare)
            } else {
                startTunnel()
            }
        } catch (e: Exception) {
    Toast.makeText(this@MainActivity, "CRASH: ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
    e.printStackTrace()
}}

        binding.btnDisconnect.setOnClickListener {
            stopTunnel()
        }

        TunnelService.status.observe(this) { s ->
            binding.tvStatus.text = s
            val connected = s.startsWith("✅")
            binding.btnConnect.isEnabled = !connected
            binding.btnDisconnect.isEnabled = connected
        }
    }

    private fun saveConfig() {
        prefs.edit()
            .putString("gateway", binding.etGateway.text.toString().trim())
            .putString("token", binding.etToken.text.toString().trim())
            .apply()
    }

    private fun startTunnel() {
        val gw = binding.etGateway.text.toString().trim()
        val token = binding.etToken.text.toString().trim()
        if (gw.isEmpty() || token.isEmpty()) {
            Toast.makeText(this@MainActivity, "Заполните поля", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this@MainActivity, TunnelService::class.java)
        intent.action = TunnelService.ACTION_START
        intent.putExtra("gateway", gw)
        intent.putExtra("token", token)
        this@MainActivity.startForegroundService(intent)
    }

    private fun stopTunnel() {
        val intent = Intent(this@MainActivity, TunnelService::class.java)
        intent.action = TunnelService.ACTION_STOP
        this@MainActivity.startService(intent)
    }
}
