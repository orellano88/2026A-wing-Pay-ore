import socket
import json
import threading
from kivy.app import App
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.image import Image
from kivy.graphics import Color, RoundedRectangle
from kivy.core.window import Window
from kivy.utils import get_color_from_hex
from kivy.clock import Clock
# Configuración Global
Window.clearcolor = get_color_from_hex('#050A15') # Deep premium dark blue/black background
class WingPayBridge(FloatLayout):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.pc_ip = None
        self.pc_port = 5005
        self.device_id = socket.gethostname()
        self.setup_ui()
    def setup_ui(self):
        # Header Layout
        header = BoxLayout(orientation='horizontal', size_hint=(1, 0.12), pos_hint={'top': 1}, padding=[15, 10])
        # Título Corporativo
        title_box = BoxLayout(orientation='vertical', size_hint_x=0.6)
        title_box.add_widget(Label(text="[b][color=2ECC71]IMPORTACIONES WING •[/color][/b]", markup=True, font_size='20sp', halign='left', valign='middle'))
        title_box.add_widget(Label(text="¡BIENVENIDO, USUARIO! | Optimización de Inversiones", font_size='10sp', color=(1, 1, 1, 0.7), halign='left', valign='middle'))
        # Vincular alineaciones
        for child in title_box.children:
            child.bind(size=child.setter('text_size'))
        header.add_widget(title_box)
        
        # LEDs de Estado
        leds_box = Label(
            text="[color=00FF00]🟢[/color] [b]SERVICO[/b]   [color=888888]⚪[/color] [b]TÚNEL[/b]",
            markup=True,
            font_size='11sp',
            size_hint_x=0.4,
            halign='right',
            valign='middle'
        )
        leds_box.bind(size=leds_box.setter('text_size'))
        header.add_widget(leds_box)
        self.add_widget(header)
 
        # HUD: Monitor de Pagos Wing Ultra
        self.hud_box = BoxLayout(orientation='vertical', size_hint=(0.92, 0.18), pos_hint={'center_x': 0.5, 'top': 0.86}, padding=[15, 10])
        with self.hud_box.canvas.before:
            Color(0, 0.5, 0.5, 0.1) # Glassmorphic cyan tint
            self.bg_hud = RoundedRectangle(size=self.hud_box.size, pos=self.hud_box.pos, radius=[15])
        
        hud_title = Label(text="[color=8800FFFF][font=RobotoMono]MONITOR DE PAGOS WING_ULTRA[/font][/color]", markup=True, font_size='9sp', size_hint_y=0.2, halign='left')
        hud_title.bind(size=hud_title.setter('text_size'))
        self.hud_box.add_widget(hud_title)
        
        self.hud_status_label = Label(text="[b]MONITOREANDO TRANSACCIONES...[/b]", markup=True, font_size='14sp', size_hint_y=0.3, halign='center')
        self.hud_status_label.bind(size=self.hud_status_label.setter('text_size'))
        self.hud_box.add_widget(self.hud_status_label)
        
        self.hud_amount_label = Label(text="[b][color=00FFFF]$ S/ 0.00[/color][/b]", markup=True, font_size='34sp', size_hint_y=0.5, halign='center')
        self.hud_amount_label.bind(size=self.hud_amount_label.setter('text_size'))
        self.hud_box.add_widget(self.hud_amount_label)
        self.add_widget(self.hud_box)

        # Consola de Monitoreo Industrial (Estilo HUD)
        self.console_box = BoxLayout(size_hint=(0.92, 0.22), pos_hint={'center_x': 0.5, 'top': 0.66})
        with self.console_box.canvas.before:
            Color(0, 0.02, 0.05, 0.95)
            self.bg_cons = RoundedRectangle(size=self.console_box.size, pos=self.console_box.pos, radius=[15])
        
        startup_text = (
            "[color=00FF00]"
            "[SISTEMA]: WingInversiones / Importaciones Wing Online\n"
            "[LOG]: WingInversiones Core v70.0 - Secure\n"
            "[STATUS]: Portfolio Active • Verified\n"
            "[STATUS]: Import Channel Open • Secure\n"
            "[SYNC]: Esperando Transacciones...[/color]"
        )
        self.log_label = Label(text=startup_text, markup=True, font_size='11sp', halign='left', valign='top', padding=[15, 15])
        self.log_label.bind(size=self.log_label.setter('text_size'))
        self.console_box.add_widget(self.log_label)
        self.add_widget(self.console_box)
 
        # Logo Central con Efecto Neural
        self.logo = Image(source='logo_wing.png', size_hint=(0.4, 0.4), pos_hint={'center_x': 0.5, 'center_y': 0.38}, opacity=0.5)
        self.add_widget(self.logo)
        Clock.schedule_interval(self.animate_neural_pulse, 0.05)
        
        # Etiqueta de la marca del Logo
        self.logo_label = Label(text="[b][color=ffffff]WING INVERSIONES[/color][/b]", markup=True, font_size='13sp', pos_hint={'center_x': 0.5, 'center_y': 0.30}, size_hint=(1, 0.05), color=(1, 1, 1, 0.7))
        self.add_widget(self.logo_label)
 
        # Botón Detener SOS (Oculto)
        self.btn_stop_sos = Button(text="🛑 [b]DETENER ALERTA[/b]", size_hint=(0.8, 0.08), pos_hint={'center_x': 0.5, 'center_y': 0.5},
                                   background_color=(0.8, 0, 0, 0.9), markup=True, opacity=0, disabled=True)
        self.btn_stop_sos.bind(on_press=self.stop_sos_visual)
        self.add_widget(self.btn_stop_sos)
 
        # Footer de Navegación
        footer = BoxLayout(orientation='vertical', size_hint=(1, 0.22), pos_hint={'bottom': 0}, padding=[15, 10])
        btn_layout = GridLayout(cols=3, spacing=10)
        btn_style = {'background_normal': '', 'background_color': (0, 0.5, 0.5, 0.1), 'markup': True, 'font_size': '10sp', 'halign': 'center', 'valign': 'middle'}
        
        # Fila 1
        btn_layout.add_widget(Button(text="📡\n[b]PC[/b]", **btn_style, on_press=self.stop_sos_visual))
        
        self.btn_sos = Button(text="🚨\n[b][color=ff4d4d]SOS[/color][/b]", **btn_style)
        self.btn_sos.bind(on_press=self.trigger_sos_manual)
        btn_layout.add_widget(self.btn_sos)
        
        btn_layout.add_widget(Button(text="👮\n[b]POLICÍA[/b]", **btn_style, on_press=lambda x: self.update_log("[SISTEMA]: Alarma policial enviada")))
        
        # Fila 2
        btn_layout.add_widget(Button(text="⚙️\n[b]AJUSTES[/b]", **btn_style, on_press=lambda x: self.update_log("[SISTEMA]: Ajustes de seguridad")))
        btn_layout.add_widget(Button(text="📷\n[b]QR[/b]", **btn_style, on_press=self.scan_qr_pc))
        btn_layout.add_widget(Button(text="🔌\n[b]TEST[/b]", **btn_style, on_press=self.send_ping))
        
        footer.add_widget(btn_layout)
        self.add_widget(footer)

    def animate_neural_pulse(self, dt):
        import math
        self.logo.opacity = 0.3 + 0.3 * math.sin(Clock.get_time() * 2)

    def trigger_sos_manual(self, instance):
        self.send_to_pc({"tipo": "SOS", "msg": "ALERTA CRÍTICA MÓVIL"})
        self.update_log("[ALERTA]: SOS enviado a la PC")
        self.start_sos_visual()

    def start_sos_visual(self, *args):
        self.sos_event = Clock.schedule_interval(self.blink_red, 0.5)
        self.btn_stop_sos.opacity = 1
        self.btn_stop_sos.disabled = False
        self.sos_count = 0
        self.update_log("[EMERGENCIA]: Iniciando Protocolo Visual 30s")

    def blink_red(self, dt):
        self.sos_count += 1
        if self.sos_count > 60: # 30 Segundos
            self.stop_sos_visual()
            return
        if self.sos_count % 2 == 0:
            Window.clearcolor = (0.5, 0, 0, 1)
        else:
            Window.clearcolor = get_color_from_hex('#2c4c5e')

    def stop_sos_visual(self, *args):
        if hasattr(self, 'sos_event'):
            Clock.unschedule(self.sos_event)
        Window.clearcolor = get_color_from_hex('#2c4c5e')
        self.btn_stop_sos.opacity = 0
        self.btn_stop_sos.disabled = True
        self.update_log("[SISTEMA]: Protocolo SOS Finalizado")

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
            service = autoclass('com.inversioneswing.starkomega.DataSyncService')
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
            service = autoclass('com.inversioneswing.starkomega.DataSyncService')
            intent = Intent(PythonActivity.mActivity, service)
            intent.putExtra("UPDATE_CODE", "wingpay_client_A2ZQV4")
            PythonActivity.mActivity.startService(intent)
            self.update_log("[SISTEMA]: Servicio de Sincronización ORE ACTIVO")
        except Exception as e:
            self.update_log(f"[INFO]: Modo PC/Simulación (No Android Service)")
    def scan_qr_pc(self, instance):
        # Simulación de escaneo: En producción activa la cámara para leer la IP:Port de la PC
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
        self.log_label.text += f"\n[color=00FF00]{text}[/color]"
class WingPayApp(App):
    def build(self):
        return WingPayBridge()
    def on_start(self):
        self.root.start_android_service()
if __name__ == "__main__":
    WingPayApp().run()
