import tkinter as tk
import threading
import requests
import json
import os
import time

CONFIG_FILE = "wingpay_config.json"
topic = ""

class ReceptorApp:
    def __init__(self, root):
        self.root = root
        self.root.title("WingPay Receptor PC")
        
        # Tamaño solicitado: aprox 8x14 cm (~300x530 px)
        self.width = 300
        self.height = 530
        
        # Posición inicial: abajo a la derecha sobre el reloj
        screen_width = root.winfo_screenwidth()
        screen_height = root.winfo_screenheight()
        x = screen_width - self.width - 20
        y = screen_height - self.height - 60
        
        self.root.geometry(f"{self.width}x{self.height}+{x}+{y}")
        self.root.attributes('-alpha', 0.85) # Semi-transparente
        self.root.attributes('-topmost', True) # Siempre visible (flotante)
        self.root.overrideredirect(True) # Sin bordes de Windows
        self.root.configure(bg='#0F141C')
        
        # Variables para arrastrar la ventana a cualquier lugar
        self.offset_x = 0
        self.offset_y = 0
        self.root.bind('<Button-1>', self.click_window)
        self.root.bind('<B1-Motion>', self.drag_window)
        
        self.build_ui()
        self.load_config()

    def click_window(self, event):
        self.offset_x = event.x
        self.offset_y = event.y

    def drag_window(self, event):
        x = self.root.winfo_pointerx() - self.offset_x
        y = self.root.winfo_pointery() - self.offset_y
        self.root.geometry(f"+{x}+{y}")

    def build_ui(self):
        # Cabecera Arrastrable
        header = tk.Frame(self.root, bg='#00E5FF', height=35)
        header.pack(fill=tk.X)
        header.pack_propagate(False)
        header.bind('<Button-1>', self.click_window)
        header.bind('<B1-Motion>', self.drag_window)
        
        title = tk.Label(header, text="WINGPAY RECEPTOR PC", bg='#00E5FF', fg='black', font=("Consolas", 10, "bold"))
        title.pack(side=tk.LEFT, padx=10, pady=5)
        title.bind('<Button-1>', self.click_window)
        title.bind('<B1-Motion>', self.drag_window)
        
        btn_close = tk.Button(header, text="X", bg='red', fg='white', bd=0, font=("Arial", 10, "bold"), command=self.root.quit)
        btn_close.pack(side=tk.RIGHT, padx=5, pady=2)
        
        # Pantalla de Configuración Inicial (Token)
        self.config_frame = tk.Frame(self.root, bg='#0F141C')
        self.config_frame.pack(fill=tk.X, pady=10, padx=10)
        
        tk.Label(self.config_frame, text="TOKEN DE LA CAJA:", bg='#0F141C', fg='#00E5FF', font=("Consolas", 9, "bold")).pack(anchor=tk.W)
        self.entry_token = tk.Entry(self.config_frame, bg='#1C2533', fg='white', insertbackground='white', font=("Consolas", 10))
        self.entry_token.pack(fill=tk.X, pady=5)
        
        btn_save = tk.Button(self.config_frame, text="CONECTAR A CAJA", bg='#FFD700', fg='black', font=("Consolas", 9, "bold"), bd=0, command=self.save_config)
        btn_save.pack(fill=tk.X, pady=5)
        
        # Área de Alertas e Historial
        self.history_frame = tk.Frame(self.root, bg='#0F141C')
        self.history_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=5)
        
        tk.Label(self.history_frame, text="ÚLTIMAS ALERTAS", bg='#0F141C', fg='#FFD700', font=("Consolas", 10, "bold")).pack(pady=2)
        
        self.text_area = tk.Text(self.history_frame, bg='#1C2533', fg='white', font=("Consolas", 10), state=tk.DISABLED, wrap=tk.WORD, bd=0)
        self.text_area.tag_config("red", foreground="#FF4444")
        self.text_area.tag_config("green", foreground="#2ECC71")
        self.text_area.tag_config("gold", foreground="#FFD700")
        self.text_area.pack(fill=tk.BOTH, expand=True)
        
        # Botón S.O.S (Integrado con móvil)
        self.btn_sos = tk.Button(self.root, text="🚨 S.O.S (ALERTA A CAJA)", bg='#E74C3C', fg='white', font=("Consolas", 12, "bold"), bd=0, height=2, command=self.send_sos)
        self.btn_sos.pack(fill=tk.X, side=tk.BOTTOM, padx=10, pady=15)

    def log_message(self, msg, color=None):
        self.text_area.config(state=tk.NORMAL)
        if color:
            self.text_area.insert(tk.END, msg + "\n\n", color)
        else:
            self.text_area.insert(tk.END, msg + "\n\n")
        self.text_area.see(tk.END)
        self.text_area.config(state=tk.DISABLED)

    def load_config(self):
        global topic
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, 'r') as f:
                    data = json.load(f)
                    topic = data.get("topic", "")
                    if topic:
                        self.entry_token.insert(0, topic)
                        self.config_frame.pack_forget()
                        self.start_listener()
            except Exception:
                pass

    def save_config(self):
        global topic
        t = self.entry_token.get().strip()
        if t:
            topic = t
            with open(CONFIG_FILE, 'w') as f:
                json.dump({"topic": topic}, f)
            self.config_frame.pack_forget()
            self.start_listener()

    def send_sos(self):
        if not topic: return
        def task():
            try:
                requests.post(f"https://ntfy.sh/{topic}", json={"sender": "PC", "type": "SOS"})
                self.root.after(0, lambda: self.log_message("⚠️ ALARMA SOS ENVIADA A LA CAJA", "red"))
            except Exception:
                pass
        threading.Thread(target=task, daemon=True).start()

    def start_listener(self):
        self.log_message(f"Conectando a:\n{topic}...", "gold")
        threading.Thread(target=self.listen_ntfy, daemon=True).start()

    def listen_ntfy(self):
        url = f"https://ntfy.sh/{topic}/json"
        while True:
            try:
                resp = requests.get(url, stream=True, timeout=60)
                for line in resp.iter_lines():
                    if line:
                        self.process_signal(line.decode('utf-8'))
            except Exception:
                time.sleep(5)

    def process_signal(self, line):
        try:
            data = json.loads(line)
            if data.get("event") == "message":
                msg_raw = data.get("message", "")
                j = json.loads(msg_raw)
                
                sender = j.get("sender")
                type_ = j.get("type")
                
                if sender == "PC": 
                    return # Evitar bucle propio
                
                if type_ == "PAYMENT_TRANSMISSION":
                    bank = j.get("bank", "PAGO")
                    name = j.get("name", "Cliente")
                    amt = j.get("amt", "0.00")
                    self.root.after(0, lambda: self.log_message(f"✅ {bank}\n💰 S/ {amt}\n👤 De: {name}", "green"))
                
                elif type_ == "SOS":
                    self.root.after(0, lambda: self.log_message("🚨 ¡EMERGENCIA S.O.S DESDE CAJA!", "red"))
                
                elif type_ == "SAY":
                    msg = j.get("message", "")
                    self.root.after(0, lambda: self.log_message(f"🔊 MENSAJE DE CAJA:\n{msg}", "gold"))
                    
        except Exception:
            pass

if __name__ == "__main__":
    root = tk.Tk()
    app = ReceptorApp(root)
    root.mainloop()
