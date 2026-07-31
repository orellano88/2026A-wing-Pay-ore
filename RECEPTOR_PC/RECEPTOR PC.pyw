import tkinter as tk
from tkinter import ttk
import threading, requests, json, time, csv, os, random, sys, queue, logging
# [PARCHE FANTASMA 1] Evita que Pygame cree ventanas invisibles al iniciar la voz
os.environ['SDL_VIDEODRIVER'] = 'dummy'
import pygame
from datetime import datetime
import winsound
import ctypes
import qrcode
import subprocess
from PIL import Image, ImageTk

def get_base_path():
    if getattr(sys, 'frozen', False):
        return getattr(sys, '_MEIPASS', os.path.dirname(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))

def get_exe_dir():
    if getattr(sys, 'frozen', False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))

# Logging mínimo para diagnóstico
logging.basicConfig(
    filename=os.path.join(get_exe_dir(), 'wingpay_error.log'),
    level=logging.WARNING,
    format='%(asctime)s [%(levelname)s] %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)

def _limpiar_mp3_temporales():
    """Limpia archivos de voz temporales huérfanos al arrancar"""
    tmp = os.environ.get('TEMP', '')
    if tmp:
        for f in os.listdir(tmp):
            if f.startswith('stark_voice_') and f.endswith('.mp3'):
                try: os.remove(os.path.join(tmp, f))
                except: pass

_limpiar_mp3_temporales()

# ==========================================================
# PROTOCOLO STARK v65.0: CENTINELA GOD - HÍBRIDO PERFECTO v56 (ESCUDO ANTI-FANTASMA)
# ==========================================================

class StarkHUD:
    def __init__(self):
        # --- [1] SEGURIDAD DE INSTANCIA (Programación v65) ---
        self.mutex = ctypes.windll.kernel32.CreateMutexW(None, True, "Global\\WingPay_HUD_Centinela_Mutex")
        if ctypes.windll.kernel32.GetLastError() == 183:  # ERROR_ALREADY_EXISTS
            sys.exit(0)

        # --- [2] CONFIGURACIÓN DE VENTANA (Visual v56 Exacto) ---
        self.root = tk.Tk()
        self.root.title("CENTINELA PRO - GOD MODE")
        import math
        self.animate_hud_step = 0
        
        self.COLOR_BG = "#0B132B" 
        self.COLOR_ACCENT = "#00ffcc" 
        self.COLOR_ACCENT_DIM = "#1C2541" 
        self.COLOR_TEXT = "#ffffff"
        self.COLOR_TEXT_DIM = "#00ffcc"
        self.COLOR_SOS = "#ff4444" 
        
        self.FONT_MAIN = ("Consolas", 8, "bold")
        self.FONT_TITLE = ("Consolas", 11, "bold")
        self.FONT_HUGE = ("Consolas", 22, "bold")
        
        sw, sh = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
        w, h = 300, 530 
        geo_str = f"{w}x{h}+{sw - w - 20}+{sh - h - 60}"
        try:
            with open(os.path.join(get_exe_dir(), "wingpay_geometry.txt"), "r") as f:
                saved_geo = f.read().strip()
                if saved_geo:
                    parts = saved_geo.split('+')
                    dims = parts[0].split('x')
                    _w, _h = int(dims[0]), int(dims[1])
                    if _h < 300: _h = 530
                    w, h = max(300, _w), max(400, _h)
                    if len(parts) >= 3:
                        _x, _y = int(parts[1]), int(parts[2])
                        if _x < 0 or _x > sw - 100: _x = sw - w - 20
                        if _y < 0 or _y > sh - 100: _y = sh - h - 60
                        pos = f"+{_x}+{_y}"
                    else:
                        pos = f"+{sw - w - 20}+{sh - h - 60}"
                    geo_str = f"{w}x{h}{pos}"
        except: pass
        self.root.geometry(geo_str)
        self.root.attributes('-topmost', True, '-alpha', 1.0)
        self.root.overrideredirect(True)
        self.root.configure(bg=self.COLOR_BG)
        self.root.deiconify()
        self.root.lift()

        self.is_shrunk = False
        self.saved_w, self.saved_h = w, h
        self.cola_voz = queue.Queue()
        self.last_msg_id = "" 
        self.last_msg_time = 0
        self.client_token = self.obtener_token_cliente()

        # --- [3] INTERFAZ CANVAS (Fondo y Decoración v56) ---
        self.canvas = tk.Canvas(self.root, bg=self.COLOR_BG, highlightthickness=0)
        self.canvas.pack(fill="both", expand=True)

        # Header con Botonera Familiar v56
        self.header = tk.Frame(self.root, bg=self.COLOR_BG, cursor="fleur")
        self.header_window_id = self.canvas.create_window(15, 15, anchor="nw", window=self.header, width=w-30)
        
        self.lbl_title = tk.Label(self.header, text="[ IMPORTACIONES WING ]", fg=self.COLOR_ACCENT, font=self.FONT_MAIN, bg=self.COLOR_BG, cursor="fleur")
        self.lbl_title.pack(side="left", pady=5)
        self.lbl_status = tk.Label(self.header, text="●", fg="#ff4444", font=("Consolas", 10), bg=self.COLOR_BG, cursor="fleur")
        self.lbl_status.pack(side="left", padx=(3, 0))

        for w_item in (self.header, self.lbl_title, self.lbl_status, self.canvas):
            w_item.bind("<ButtonPress-1>", self.start_move)
            w_item.bind("<B1-Motion>", self.do_move)
            w_item.bind("<ButtonRelease-1>", self.save_geometry)
        self.btn_close = tk.Button(self.header, text="[X]", command=self.cleanup_and_exit, fg="red", bg=self.COLOR_BG, bd=0, font=self.FONT_MAIN, activebackground=self.COLOR_BG, cursor="tcross")
        self.btn_close.pack(side="right")
        self.btn_toggle = tk.Button(self.header, text="[-]", command=self.toggle_size, fg=self.COLOR_ACCENT, bg=self.COLOR_BG, bd=0, font=self.FONT_MAIN, activebackground=self.COLOR_BG, cursor="tcross")
        self.btn_toggle.pack(side="right", padx=5)
        self.btn_qr = tk.Button(self.header, text="[⚙️]", command=self.mostrar_qr_independiente, fg=self.COLOR_ACCENT, bg=self.COLOR_BG, bd=0, font=self.FONT_MAIN, activebackground=self.COLOR_BG, cursor="tcross")
        self.btn_qr.pack(side="right", padx=5)
        self.btn_sos = tk.Button(self.header, text="[SOS]", command=self.enviar_alerta_sos, fg=self.COLOR_SOS, bg=self.COLOR_BG, bd=0, font=self.FONT_MAIN, activebackground="#550000", cursor="tcross")
        self.btn_sos.pack(side="right", padx=5)

        def add_btn_hover(btn, hover_bg, hover_fg, orig_bg, orig_fg):
            btn.bind("<Enter>", lambda e: btn.config(bg=hover_bg, fg=hover_fg))
            btn.bind("<Leave>", lambda e: btn.config(bg=orig_bg, fg=orig_fg))
            
        add_btn_hover(self.btn_close, "#ff4444", "white", self.COLOR_BG, "red")
        add_btn_hover(self.btn_toggle, self.COLOR_ACCENT_DIM, self.COLOR_ACCENT, self.COLOR_BG, self.COLOR_ACCENT)
        add_btn_hover(self.btn_qr, self.COLOR_ACCENT_DIM, self.COLOR_ACCENT, self.COLOR_BG, self.COLOR_ACCENT)
        add_btn_hover(self.btn_sos, "#ff0000", "white", self.COLOR_BG, self.COLOR_SOS)

        # Contenedor de Paneles (Márgenes v56)
        self.content = tk.Frame(self.root, bg=self.COLOR_BG)
        self.content_window_id = self.canvas.create_window(15, 50, anchor="nw", window=self.content, width=w-30, height=h-70)
        self.content.columnconfigure(0, weight=1)
        self.content.rowconfigure(2, weight=1)

        # Panel Pago v56
        self.panel_pago = self.create_hud_panel(self.content, 0)
        tk.Label(self.panel_pago, text="> ÚLTIMO_INGRESO.exe", fg=self.COLOR_TEXT_DIM, bg=self.COLOR_BG, font=self.FONT_MAIN).pack(anchor="w", padx=10, pady=(10, 0))
        self.lbl_name = tk.Label(self.panel_pago, text="ESPERANDO DATOS...", fg=self.COLOR_TEXT, bg=self.COLOR_BG, font=self.FONT_TITLE)
        self.lbl_name.pack(anchor="w", padx=10, pady=(5,0))
        self.lbl_amt = tk.Label(self.panel_pago, text="S/ 0.00", fg=self.COLOR_ACCENT, bg=self.COLOR_BG, font=self.FONT_HUGE)
        self.lbl_amt.pack(anchor="w", padx=10, pady=(0, 10))

        # Panel Frase IA v56 (Typewriter con Explicación)
        self.panel_frase = self.create_hud_panel(self.content, 1)
        tk.Label(self.panel_frase, text="> IA_CONSEJERO.sys", fg=self.COLOR_TEXT_DIM, bg=self.COLOR_BG, font=self.FONT_MAIN).pack(anchor="w", padx=10, pady=(10, 0))
        self.frame_frase_text = tk.Frame(self.panel_frase, bg=self.COLOR_BG, height=65)
        self.frame_frase_text.pack(fill="both", expand=True, padx=10, pady=5)
        self.frame_frase_text.pack_propagate(False)
        self.lbl_frase = tk.Label(self.frame_frase_text, text="", fg=self.COLOR_ACCENT, bg=self.COLOR_BG, font=("Consolas", 9), wraplength=250, justify="left")
        self.lbl_frase.pack(anchor="nw")
        self.lbl_explicacion = tk.Label(self.frame_frase_text, text="", fg="#aaaaaa", bg=self.COLOR_BG, font=("Consolas", 8, "italic"), wraplength=250, justify="left")
        self.lbl_explicacion.pack(anchor="nw", pady=(2, 0))
        tk.Button(self.panel_frase, text="[ RECALCULAR ]", command=self.cambiar_frase, bg=self.COLOR_BG, fg=self.COLOR_ACCENT, bd=1, relief="solid", font=("Consolas", 7), cursor="tcross").pack(anchor="e", padx=10, pady=(0, 10))

        # Panel Historial con SCROLLBAR Cyberpunk (v56 Style)
        self.panel_historial = tk.Frame(self.content, bg=self.COLOR_BG)
        self.panel_historial.grid(row=2, column=0, sticky="nsew", pady=(5, 5))
        tk.Label(self.panel_historial, text="> TELEMETRÍA", fg=self.COLOR_TEXT_DIM, bg=self.COLOR_BG, font=self.FONT_MAIN).pack(anchor="w", pady=(0, 5))
        
        style = ttk.Style(); style.theme_use("default")
        style.configure("Treeview", background=self.COLOR_BG, foreground=self.COLOR_TEXT, fieldbackground=self.COLOR_BG, borderwidth=0, font=("Consolas", 8), rowheight=25)
        style.map("Treeview", background=[('selected', self.COLOR_ACCENT_DIM)], foreground=[('selected', self.COLOR_ACCENT)])
        style.configure("Treeview.Heading", background="#111111", foreground=self.COLOR_ACCENT, font=self.FONT_MAIN, borderwidth=1, relief="solid")
        style.configure("Vertical.TScrollbar", background=self.COLOR_BG, troughcolor=self.COLOR_BG, arrowcolor=self.COLOR_ACCENT, bordercolor=self.COLOR_ACCENT_DIM)

        self.tree_frame = tk.Frame(self.panel_historial, bg=self.COLOR_BG)
        self.tree_frame.pack(fill="both", expand=True)
        self.tree_scroll = ttk.Scrollbar(self.tree_frame, orient="vertical")
        self.tree_scroll.pack(side="right", fill="y")
        
        self.tree = ttk.Treeview(self.tree_frame, columns=("ID", "AMT", "TIME"), show="headings", yscrollcommand=self.tree_scroll.set)
        self.tree_scroll.config(command=self.tree.yview)
        self.tree.heading("ID", text="[ID]"); self.tree.column("ID", width=120, anchor="w")
        self.tree.heading("AMT", text="[S/.]"); self.tree.column("AMT", width=60, anchor="center")
        self.tree.heading("TIME", text="[TIME]"); self.tree.column("TIME", width=60, anchor="center")
        self.tree.pack(side="left", fill="both", expand=True)

        # Grip Decorativo v56
        self.lbl_grip = tk.Label(self.root, text="///", fg=self.COLOR_ACCENT_DIM, bg=self.COLOR_BG, font=("Consolas", 10, "bold"), cursor="bottom_right_corner")
        self.lbl_grip.bind("<B1-Motion>", self.resize_window)
        self.lbl_grip.bind("<ButtonRelease-1>", self.save_geometry)

        # --- [5] ARRANQUE Y THREADS ---
        self.lista_frases = self.cargar_todas_las_frases()
        self.typewriter_idx = 0
        self.typewriter_mode = "frase"
        self.iniciar_typewriter()
        self.dibujar_hud_decorations()
        
        # [ORDEN SEGURO v65.5] Primero cargar datos, luego aplicar bordes al final
        self.root.after(100, self.cargar_historial_local)
        self.root.after(500, lambda: self.aplicar_bordes(self.saved_w, self.saved_h))
        
        threading.Thread(target=self.ntfy_listener, daemon=True).start()
        threading.Thread(target=self.procesador_voz, daemon=True).start()
        self.root.mainloop()

    # ======================================================
    # LÓGICA DE PROGRAMACIÓN (ESTILO V2 + BLINDAJE)
    # ======================================================

    def obtener_token_cliente(self):
        base_path = get_exe_dir()
        path = os.path.join(base_path, "wingpay_client_token.txt")
        if os.path.exists(path):
            try:
                with open(path, "rb") as f:
                    content = f.read()
                if content.startswith(b'\xff\xfe'):
                    token = content.decode('utf-16').strip()
                elif content.startswith(b'\xfe\xff'):
                    token = content.decode('utf-16-be').strip()
                else:
                    try:
                        token = content.decode('utf-8').strip()
                    except:
                        token = content.decode('cp1252', errors='ignore').strip()
                token = "".join(c for c in token if c.isalnum() or c in "_-")
                if token:
                    return token
            except:
                pass
        token = "wingpay_client_" + "".join(random.choices("ABCDEFGHJKLMNPQRSTUVWXYZ23456789", k=6))
        with open(path, "w", encoding="utf-8") as f: f.write(token)
        return token

    def cargar_todas_las_frases(self):
        # Obtener la ruta del directorio donde está el script/exe
        base_path = get_base_path()
        ruta = os.path.join(base_path, "frases_exito.txt")
        if not os.path.exists(ruta): return [("SISTEMA_OPERATIVO", "Esperando interceptación de datos...")]
        with open(ruta, "r", encoding="utf-8") as f:
            return [l.strip().split('|') for l in f.readlines() if '|' in l]

    def iniciar_typewriter(self):
        if not self.lista_frases: return
        f = random.choice(self.lista_frases)
        self.current_frase_target = f[0]; self.current_exp_target = f[1] if len(f)>1 else ""
        self.lbl_frase.config(text=""); self.lbl_explicacion.config(text="")
        self.typewriter_idx = 0; self.typewriter_mode = "frase"
        self.typewriter_step()

    def typewriter_step(self):
        if self.typewriter_mode == "frase":
            if self.typewriter_idx < len(self.current_frase_target):
                self.lbl_frase.config(text=self.current_frase_target[:self.typewriter_idx+1] + "█")
                self.typewriter_idx += 1; self.root.after(30, self.typewriter_step)
            else:
                self.lbl_frase.config(text=self.current_frase_target)
                self.typewriter_mode = "explicacion"; self.typewriter_idx = 0; self.root.after(200, self.typewriter_step)
        elif self.typewriter_mode == "explicacion":
            if self.typewriter_idx < len(self.current_exp_target):
                self.lbl_explicacion.config(text=self.current_exp_target[:self.typewriter_idx+1] + "█")
                self.typewriter_idx += 1; self.root.after(15, self.typewriter_step)
            else:
                self.lbl_explicacion.config(text=self.current_exp_target)
                self.typewriter_mode = "blink"; self.typewriter_idx = 0; self.root.after(500, self.typewriter_step)
        elif self.typewriter_mode == "blink":
            text = self.current_exp_target if self.current_exp_target else self.current_frase_target
            lbl = self.lbl_explicacion if self.current_exp_target else self.lbl_frase
            if self.typewriter_idx % 2 == 0:
                lbl.config(text=text + "█")
            else:
                lbl.config(text=text)
            self.typewriter_idx += 1
            self.root.after(500, self.typewriter_step)

    def cambiar_frase(self): self.iniciar_typewriter()

    def dibujar_hud_decorations(self):
        self.canvas.delete("hud")
        w, h = self.root.winfo_width(), self.root.winfo_height()
        color = self.COLOR_ACCENT
        self.canvas.create_rectangle(1, 1, w-1, h-1, outline=self.COLOR_ACCENT_DIM, width=2, tags="hud")
        L = 15
        self.canvas.create_line(15, 25, 15+L, 25, fill=color, width=2, tags="hud")
        self.canvas.create_line(25, 15, 25, 15+L, fill=color, width=2, tags="hud")
        self.canvas.create_line(w-15, 25, w-15-L, 25, fill=color, width=2, tags="hud")
        self.canvas.create_line(w-25, 15, w-25, 15+L, fill=color, width=2, tags="hud")
        self.canvas.create_line(15, h-25, 15+L, h-25, fill=color, width=2, tags="hud")
        self.canvas.create_line(25, h-15, 25, h-15-L, fill=color, width=2, tags="hud")
        if not self.is_shrunk: self.lbl_grip.place(x=w-25, y=h-25)
        else: self.lbl_grip.place_forget()

    def ntfy_listener(self):
        url = f"https://ntfy.sh/{self.client_token}/json"
        while True:
            try:
                self.root.after(0, lambda: self.lbl_status.config(fg="#00ff88"))
                with requests.get(url, stream=True, timeout=45) as r:
                    for line in r.iter_lines():
                        if not line: continue
                        data = json.loads(line)
                        if "message" not in data: continue
                        raw_msg = data["message"]
                        try:
                            msg = json.loads(raw_msg)
                            if msg.get("sender") == "PC": continue
                            msg_id = f"{msg.get('name')}_{msg.get('amt')}_{msg.get('type')}"
                            now = time.time()
                            if msg_id == self.last_msg_id and (now - self.last_msg_time) < 4: continue
                            self.last_msg_id = msg_id; self.last_msg_time = now
                            self.root.after(0, self.procesar_evento, msg)
                        except Exception as e:
                            logging.warning(f"Error parseando mensaje ntfy: {e}")
            except Exception as e:
                self.root.after(0, lambda: self.lbl_status.config(fg="#ff4444"))
                logging.warning(f"Conexión ntfy perdida: {e}")
                time.sleep(5)

    def procesar_evento(self, msg):
        hora = datetime.now().strftime("%H:%M:%S")
        if msg.get("type") == "SOS": 
            self.activar_alarma_pc()
        elif msg.get("type") == "SAY" or msg.get("sender") == "PHONE_PANIC":
            self.activar_alerta_policial(msg.get("message", "ALERTA DE SEGURIDAD"))
        else:
            name, amt = msg.get('name', 'STARK'), msg.get('amt', '0.00')
            threading.Thread(target=lambda: (winsound.MessageBeep(), winsound.Beep(1000, 150)), daemon=True).start()
            self.lbl_name.config(text=f"> {name.upper()}")
            
            try:
                target_amt = float(amt)
                self.animar_monto(0.0, target_amt, 20)
            except:
                self.lbl_amt.config(text=f"S/ {amt}")
                
            self.tree.insert("", 0, values=(name, amt, hora))
            self.registrar_pago_local(name, amt, hora)
            try:
                monto_float = float(amt)
                soles_int = int(monto_float)
                centimos_int = int(round((monto_float - soles_int) * 100))
                if centimos_int > 0:
                    str_monto = f"{soles_int} soles con {centimos_int} céntimos"
                else:
                    str_monto = f"{soles_int} soles"
            except Exception:
                str_monto = f"{amt} soles"
            
            self.cola_voz.put(f"Pago detectado. {name}, transfirió {str_monto}.")
            if self.is_shrunk: self.toggle_size()

    def animar_monto(self, current, target, steps_left):
        if steps_left <= 0:
            self.lbl_amt.config(text=f"S/ {target:.2f}")
            return
        current += (target - current) / steps_left
        self.lbl_amt.config(text=f"S/ {current:.2f}")
        self.root.after(30, self.animar_monto, current, target, steps_left - 1)

    def activar_alerta_policial(self, texto):
        self.root.configure(bg="#000033")
        self.cola_voz.put(texto)
        def _police_beeps():
            for _ in range(4):
                winsound.Beep(1500, 150)
                winsound.Beep(1000, 150)
        threading.Thread(target=_police_beeps, daemon=True).start()
        it = self.tree.insert("", 0, values=("ALERTA POLICIAL", "0.00", datetime.now().strftime("%H:%M:%S")))
        self.tree.tag_configure('police', background='#4444ff', foreground='white')
        self.tree.item(it, tags=('police',))
        self.root.after(5000, lambda: self.root.configure(bg=self.COLOR_BG))

    def cargar_historial_local(self):
        self.intentar_sincronizar_csv()
        fecha_hoy = datetime.now().strftime("%d-%m-%Y")
        path = os.path.join(os.path.expanduser("~"), "Downloads", f"yapes_{fecha_hoy}.csv")
        if not os.path.exists(path): return
        try:
            with open(path, "r", encoding="utf-8") as f:
                reader = csv.DictReader(f)
                filas = list(reader)
                for row in reversed(filas[-20:]): 
                    self.tree.insert("", "end", values=(row['Cliente'], row['Monto'], row['Hora']))
        except Exception as e: logging.warning(f"Error cargando historial: {e}")

    def registrar_pago_local(self, name, amt, hora):
        fecha_hoy = datetime.now().strftime("%d-%m-%Y")
        journal_path = os.path.join(get_exe_dir(), "wingpay_journal.json")
        
        # 1. Cargar journal existente
        journal = []
        if os.path.exists(journal_path):
            try:
                with open(journal_path, "r", encoding="utf-8") as f:
                    journal = json.load(f)
            except:
                pass
        
        # 2. Agregar nuevo pago al journal
        journal.append({
            "name": name,
            "amt": amt,
            "hora": hora,
            "fecha": fecha_hoy
        })
        
        # 3. Guardar journal actualizado
        try:
            with open(journal_path, "w", encoding="utf-8") as f:
                json.dump(journal, f, indent=4)
        except Exception as e:
            logging.warning(f"Error escribiendo en journal: {e}")

        # 4. Intentar sincronizar con el CSV
        self.intentar_sincronizar_csv()

    def intentar_sincronizar_csv(self):
        journal_path = os.path.join(get_exe_dir(), "wingpay_journal.json")
        if not os.path.exists(journal_path): return
        try:
            with open(journal_path, "r", encoding="utf-8") as f:
                journal = json.load(f)
        except Exception as e:
            logging.warning(f"Error leyendo journal para sync: {e}")
            return
        if not journal: return

        por_fecha = {}
        for item in journal:
            fecha = item.get("fecha")
            if fecha:
                por_fecha.setdefault(fecha, []).append(item)

        exito_fechas = set()
        for fecha, items in por_fecha.items():
            path = os.path.join(os.path.expanduser("~"), "Downloads", f"yapes_{fecha}.csv")
            es_nuevo = not os.path.exists(path)
            try:
                with open(path, "a", newline="", encoding="utf-8") as f:
                    writer = csv.writer(f)
                    if es_nuevo: writer.writerow(["Cliente", "Monto", "Hora"])
                    for item in items:
                        writer.writerow([item["name"], item["amt"], item["hora"]])
                exito_fechas.add(fecha)
            except Exception as e:
                logging.warning(f"CSV de fecha {fecha} bloqueado o inaccesible (Excel abierto?): {e}")

        nuevo_journal = [item for item in journal if item.get("fecha") not in exito_fechas]
        try:
            with open(journal_path, "w", encoding="utf-8") as f:
                json.dump(nuevo_journal, f, indent=4)
        except Exception as e:
            logging.warning(f"Error actualizando journal post-sync: {e}")

    def activar_alarma_pc(self):
        self.root.configure(bg="#330000")
        self.cola_voz.put("Wilson, alerta de pánico detectada. El sistema móvil está en emergencia.")
        def _sos_beeps():
            for _ in range(3):
                winsound.Beep(1200, 300)
                winsound.Beep(800, 300)
        threading.Thread(target=_sos_beeps, daemon=True).start()
        it = self.tree.insert("", 0, values=("EMERGENCIA SOS", "0.00", datetime.now().strftime("%H:%M:%S")))
        self.tree.tag_configure('sos', background='#ff4444', foreground='white'); self.tree.item(it, tags=('sos',))
        self.root.after(5000, lambda: self.root.configure(bg=self.COLOR_BG))

    def enviar_alerta_sos(self):
        def _enviar():
            try:
                headers = {"Title": "ALERTA SOS", "Priority": "5", "Tags": "rotating_light,warning"}
                r = requests.post(f"https://ntfy.sh/{self.client_token}", data=json.dumps({"type": "SOS", "sender": "PC"}).encode('utf-8'), headers=headers, timeout=10)
                logging.warning(f"SOS HTTP: {r.status_code}")
            except Exception as e:
                logging.error(f"Error enviando SOS: {e}")
        threading.Thread(target=_enviar, daemon=True).start()

    def mostrar_qr_independiente(self):
        qr_w = tk.Toplevel(self.root); qr_w.title("VINCULACIÓN"); qr_w.configure(bg=self.COLOR_BG); qr_w.geometry("300x400"); qr_w.attributes('-topmost', True)
        qr = qrcode.QRCode(version=1, box_size=8, border=2); qr.add_data(self.client_token); qr.make(fit=True)
        img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
        img_path = os.path.join(os.environ.get('TEMP', ''), "tqr_fam_v56.png"); img.save(img_path)
        self.photo_qr_ext = ImageTk.PhotoImage(Image.open(img_path))
        lbl = tk.Label(qr_w, image=self.photo_qr_ext, bg="white", bd=5, relief="ridge"); lbl.image = self.photo_qr_ext; lbl.pack(pady=20)
        tk.Label(qr_w, text=f"TOKEN: {self.client_token}", fg=self.COLOR_ACCENT, bg=self.COLOR_BG, font=self.FONT_MAIN).pack()
        tk.Button(qr_w, text="[ CERRAR ]", command=qr_w.destroy, bg="#ff4444", fg="white", bd=0, font=self.FONT_MAIN).pack(pady=10)

    def procesador_voz(self):
        mixer_listo = False
        try:
            pygame.mixer.init()
            mixer_listo = True
        except Exception as e:
            logging.warning(f"Pygame mixer no disponible: {e}")
        while True:
            try:
                texto = self.cola_voz.get(timeout=1)
            except queue.Empty:
                continue
            temp = os.path.join(os.environ.get('TEMP', ''), "stark_voice_current.mp3")
            cmd = ['edge-tts', '--voice', 
                   'es-PE-CamilaNeural', '--rate=-15%', '--volume=+50%', '--text', texto, '--write-media', temp]
            tts_exito = False
            try:
                res = subprocess.run(cmd, creationflags=0x08000000) 
                if res.returncode == 0 and mixer_listo and os.path.exists(temp):
                    loaded = False
                    for _ in range(10):
                        try:
                            pygame.mixer.music.load(temp)
                            loaded = True
                            break
                        except Exception:
                            time.sleep(0.1)
                    if loaded:
                        pygame.mixer.music.play()
                        while pygame.mixer.music.get_busy(): time.sleep(0.1)
                        pygame.mixer.music.unload()
                        tts_exito = True
                    else:
                        logging.warning("No se pudo cargar el archivo de voz temporal (archivo bloqueado)")
            except Exception as e:
                logging.warning(f"Error en TTS: {e}")
            finally:
                if os.path.exists(temp):
                    try: os.remove(temp)
                    except: pass

            # Respaldo de Voz Offline si edge-tts no pudo emitir sonido
            if not tts_exito:
                try:
                    clean_text = texto.replace("'", "")
                    ps_cmd = f'powershell -Command "Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak(\'{clean_text}\')"'
                    subprocess.run(ps_cmd, creationflags=0x08000000)
                except Exception as e:
                    logging.warning(f"Error en voz de respaldo: {e}")

    def create_hud_panel(self, parent, r):
        p = tk.Frame(parent, bg=self.COLOR_BG, highlightbackground=self.COLOR_ACCENT_DIM, highlightthickness=1)
        p.grid(row=r, column=0, sticky="nsew", padx=2, pady=2); return p

    def aplicar_bordes(self, w=None, h=None):
        if not self.root.winfo_exists(): return
        
        # [BLINDAJE v65.6] Opacidad total temporal para engañar al gestor de sombras de Windows
        self.root.attributes('-alpha', 1.0)
        self.root.update_idletasks()
        
        if w is None or h is None:
            w = self.root.winfo_width()
            h = self.root.winfo_height()

        hwnd = self.root.winfo_id()

        # [PARCHE 1] DESACTIVAR RENDERIZADO NO-CLIENTE (Sombras)
        try:
            DWMWA_NCRENDERING_POLICY = 2
            DWMNCRP_DISABLED = 1
            ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_NCRENDERING_POLICY, ctypes.byref(ctypes.c_int(DWMNCRP_DISABLED)), 4)
            
            # Aplicar también al padre si existe (necesario en Windows 10)
            parent = ctypes.windll.user32.GetParent(hwnd)
            if parent:
                ctypes.windll.dwmapi.DwmSetWindowAttribute(parent, DWMWA_NCRENDERING_POLICY, ctypes.byref(ctypes.c_int(DWMNCRP_DISABLED)), 4)
        except: pass

        # [PARCHE 2] REDONDEO DE PRECISIÓN
        try:
            radio = 25 if not self.is_shrunk else 0
            if not self.is_shrunk:
                region = ctypes.windll.gdi32.CreateRoundRectRgn(0, 0, w, h, radio, radio)
                ctypes.windll.user32.SetWindowRgn(hwnd, region, True)
            else:
                ctypes.windll.user32.SetWindowRgn(hwnd, None, True)
        except: pass

        # [REFRESCO] Forzar a Windows a olvidar el rectángulo viejo
        ctypes.windll.user32.SetWindowPos(hwnd, 0, 0, 0, 0, 0, 0x0020 | 0x0002 | 0x0001 | 0x0004)
        
        # Restaurar la transparencia deseada una vez que la sombra ha sido eliminada
        self.root.attributes('-alpha', 0.90)
        
        if not self.is_shrunk: self.lbl_grip.place(x=w-25, y=h-25)
        else: self.lbl_grip.place_forget()
        self.dibujar_hud_decorations()

    def start_move(self, event):
        self.x = event.x_root - self.root.winfo_x()
        self.y = event.y_root - self.root.winfo_y()

    def do_move(self, event):
        x = event.x_root - self.x
        y = event.y_root - self.y
        self.root.geometry(f"+{x}+{y}")

    def resize_window(self, event):
        if self.is_shrunk: return
        w = max(300, event.x_root - self.root.winfo_rootx()); h = max(400, event.y_root - self.root.winfo_rooty())
        self.root.geometry(f"{w}x{h}")
        self.saved_w, self.saved_h = w, h
        self.canvas.itemconfigure(self.header_window_id, width=w-30)
        self.canvas.itemconfigure(self.content_window_id, width=w-30, height=h-70)
        self.root.update_idletasks()
        self.aplicar_bordes(w, h)

    def save_geometry(self, event=None):
        try:
            x, y = self.root.winfo_x(), self.root.winfo_y()
            if not self.is_shrunk:
                w = self.root.winfo_width()
                h = self.root.winfo_height()
                if w >= 300 and h >= 300:
                    self.saved_w, self.saved_h = w, h
            geo_str = f"{self.saved_w}x{self.saved_h}+{x}+{y}"
            with open(os.path.join(get_exe_dir(), "wingpay_geometry.txt"), "w") as f:
                f.write(geo_str)
        except: pass

    def toggle_size(self):
        if not self.is_shrunk: 
            curr_w, curr_h = self.root.winfo_width(), self.root.winfo_height()
            if curr_h >= 300:
                self.saved_w, self.saved_h = curr_w, curr_h
            self.saved_x, self.saved_y = self.root.winfo_x(), self.root.winfo_y()
            self.canvas.itemconfigure(self.content_window_id, state='hidden')
            self.root.geometry(f"{self.saved_w}x45+{self.saved_x}+{self.saved_y}")
            self.is_shrunk = True
            self.root.update_idletasks()
            self.aplicar_bordes(self.saved_w, 45)
            self.save_geometry()
        else: 
            self.canvas.itemconfigure(self.content_window_id, state='normal')
            self.root.geometry(f"{self.saved_w}x{self.saved_h}+{self.saved_x}+{self.saved_y}")
            self.is_shrunk = False
            self.root.update_idletasks()
            self.aplicar_bordes(self.saved_w, self.saved_h)
            self.save_geometry()

    def cleanup_and_exit(self):
        self.save_geometry()
        try:
            ctypes.windll.kernel32.ReleaseMutex(self.mutex)
            ctypes.windll.kernel32.CloseHandle(self.mutex)
        except: pass
        self.root.destroy()

if __name__ == "__main__": StarkHUD()
