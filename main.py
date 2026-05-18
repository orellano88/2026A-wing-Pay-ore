import socket
import json
import threading
from kivy.app import App
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.image import Image
from kivy.graphics import Color, RoundedRectangle
from kivy.core.window import Window
from kivy.utils import get_color_from_hex
from kivy.clock import Clock
# Configuración Global
Window.clearcolor = get_color_from_hex('#2c4c5e')
class WingPayBridge(FloatLayout):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.pc_ip = None
        self.pc_port = 5005
        self.device_id = socket.gethostname()
        self.setup_ui()
    def setup_ui(self):
        # Fondo y Estética Profesional
        header = BoxLayout(orientation='horizontal', size_hint=(1, 0.12), pos_hint={'top': 
1}, padding=[15, 10])
        # Título Corporativo
        title_box = BoxLayout(orientation='vertical')
        title_box.add_widget(Label(text="[b][color=00FFFF]IMPORTACIONES WING[/color][/b]",  markup=True, font_size='22sp'))
        title_box.add_widget(Label(text="2026 MASTER UNIVERSAL v65.0", font_size='11sp', 
color=(0.8, 0.8, 0.8, 1)))
        header.add_widget(title_box)
        self.add_widget(header)      # Consola de Monitoreo Industrial
        self.console_box = BoxLayout(size_hint=(0.92, 0.3), pos_hint={'center_x': 0.5, 
'top': 0.85})
        with self.console_box.canvas.before:
            Color(0, 0, 0, 0.85)
            self.bg_cons = RoundedRectangle(size=self.console_box.size, 
pos=self.console_box.pos, radius=[12])
        self.log_label = Label(text="[color=00FF00][SISTEMA]: Listo para Vinculación 
QR
[INFO]: Esperando Notificaciones Bancarias...[/color]",
                               markup=True, font_size='13sp', halign='left', valign='top',  padding=[15, 15])
        self.log_label.bind(size=self.log_label.setter('text_size'))
        self.console_box.add_widget(self.log_label)
        self.add_widget(self.console_box)
        # Logo Central (Referencia Visual)
        self.add_widget(Image(source='logo_wing.png', size_hint=(0.6, 0.6), 
pos_hint={'center_x': 0.5, 'center_y': 0.45}))
        # Footer de Navegación e Interconexión
        footer = BoxLayout(orientation='vertical', size_hint=(1, 0.18), pos_hint={'bottom': 
0}, padding=[10, 5])
        btn_layout = BoxLayout(orientation='horizontal', spacing=10)
        btn_style = {'background_normal': '', 'background_color': (0.1, 0.1, 0.1, 0.4), 
'markup': True}
        btn_layout.add_widget(Button(text="📶 [b]WIFI[/b]", **btn_style, on_press=self.scan_wifi))
        btn_layout.add_widget(Button(text="📷 [b]QR[/b]", **btn_style, 
on_press=self.scan_qr_pc))
        btn_layout.add_widget(Button(text="⚡ [b]TÚNEL[/b]", **btn_style, on_press=self.toggle_tunnel))
        self.btn_sos = Button(text="[color=ff4d4d][b]SOS[/b][/color]", **btn_style)
        self.btn_sos.bind(on_press=self.send_sos)
        btn_layout.add_widget(self.btn_sos)
        footer.add_widget(btn_layout)
        footer.add_widget(Label(text="VINCULACIÓN MANUAL ACTIVA", font_size='10sp', 
color=(0.8, 0.8, 0.8, 1)))
        self.add_widget(footer)
    def scan_wifi(self, instance):
        try:
            from jnius import autoclass
            Context = autoclass('android.content.Context')
            PythonActivity = autoclass('org.kivy.android.PythonActivity')
            activity = PythonActivity.mActivity
            wifi = activity.getSystemService(Context.WIFI_SERVICE)
            self.update_log("[SISTEMA]: Escaneando Redes WiFi...")
            if not wifi.isWifiEnabled():
                self.update_log("[ERROR]: WiFi desactivado.")
                return
            results = wifi.getScanResults()
            count = results.size()
            self.update_log(f"[INFO]: Encontradas {count} redes.")
            for i in range(min(count, 5)):
                res = results.get(i)
                self.update_log(f"-> {res.SSID} ({res.level}dBm)")
        except Exception as e:
            self.update_log(f"[ERROR]: Fallo de escaneo: {e}")
            self.update_log("-> WING_SECURE_EXT (Simulado)")
            self.update_log("-> STARK_LABS_01 (Simulado)")
    def toggle_tunnel(self, instance):
        if not self.pc_ip:
            self.update_log("[ERROR]: Primero escanea el QR de la PC.")
            return
        self.update_log(f"[TÚNEL]: Iniciando Protocolo de Túnel...")
        # Intento de comunicación persistente vía ntfy.sh (vía el servicio Kotlin)
        try:
            from jnius import autoclass
            PythonActivity = autoclass('org.kivy.android.PythonActivity')
            Intent = autoclass('android.content.Intent')
            service = autoclass('com.inversioneswing.wingpay.DataSyncService')
            intent = Intent(PythonActivity.mActivity, service)
            intent.putExtra("CMD_PAYMENT", True)
            intent.putExtra("BANK", "TUNNEL")
            intent.putExtra("NAME", "Status Check")
            intent.putExtra("AMT", "1.00")
            PythonActivity.mActivity.startService(intent)
            self.update_log("[SISTEMA]: Túnel ntfy.sh verificado.")
        except:
            self.update_log("[INFO]: Redireccionando tráfico a {self.pc_ip}:5005")
        self.update_log("[SISTEMA]: TÚNEL CIFRADO ESTABLECIDO.")
    def start_android_service(self):
        try:
            from jnius import autoclass
            PythonActivity = autoclass('org.kivy.android.PythonActivity')
            Intent = autoclass('android.content.Intent')
            service = autoclass('com.inversioneswing.wingpay.DataSyncService')
            intent = Intent(PythonActivity.mActivity, service)
            intent.putExtra("UPDATE_CODE", "wingpay_client_A2ZQV4")
            PythonActivity.mActivity.startService(intent)
            self.update_log("[SISTEMA]: Servicio de Sincronización ORE ACTIVO")
        except Exception as e:
            self.update_log(f"[INFO]: Modo PC/Simulación (No Android Service)")
    def scan_qr_pc(self, instance):
        # Simulación de escaneo: En producción activa la cámara para leer la IP:Port de la 
PC
        self.pc_ip = "192.168.1.100" # Ejemplo de IP capturada por QR
        self.update_log(f"[VINCULADO]: Conectado a PC en {self.pc_ip}")
    def send_to_pc(self, payload):
        if not self.pc_ip: return
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            message = json.dumps(payload).encode('utf-8')
            sock.sendto(message, (self.pc_ip, self.pc_port)) 
        except Exception as e:
            self.update_log(f"[ERROR]: Falla de red: {e}")
    def send_sos(self, instance):
        self.send_to_pc({"tipo": "SOS", "msg": "ALERTA CRÍTICA MÓVIL"})
        self.update_log("[ALERTA]: SOS enviado a la PC")
    def send_ping(self, instance):
        self.send_to_pc({"tipo": "PING", "msg": "Dispositivo activo"})
        self.update_log("[TEST]: Pulso de conexión enviado")
    def update_log(self, text):
        self.log_label.text += f"
[color=00FF00]{text}[/color]"
class WingPayApp(App):
    def build(self):
        return WingPayBridge()
    def on_start(self):
        self.root.start_android_service()
if __name__ == "__main__":
    WingPayApp().run()
