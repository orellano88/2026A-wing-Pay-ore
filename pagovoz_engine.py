# ==============================================================================
# WINGPAY CORE MAXIMA CAPACIDAD - ENGINE OPTIMIZADO CON ALGORITMOS DE PAGOVOZ
# ==============================================================================

import re
import math
import time

class PagoVozEngineEngine:
    """
    Engine de procesamiento de notificaciones y voz extraído y optimizado 
    de la descompilación de PagoVoz (com.pagovoz.app).
    """

    @staticmethod
    def clean_amount_string(amount_str):
        if not amount_str:
            return "0"
        # Remover caracteres no numéricos excepto coma y punto
        clean_str = re.sub(r'[^\d.,]', '', amount_str).strip('.,')
        
        if ',' in clean_str and '.' in clean_str:
            if clean_str.rfind('.') > clean_str.rfind(','):
                return clean_str.replace(',', '')
            else:
                return clean_str.replace('.', '').replace(',', '.')
        
        if ',' in clean_str:
            if len(clean_str) - clean_str.rfind(',') == 3:
                return re.sub(r',(?=\d{2}$)', '.', clean_str)
            return clean_str.replace(',', '')
            
        return clean_str

    @staticmethod
    def extract_amount(text):
        if not text:
            return 0.0
        
        # 1. Regex de patrón S/ (Yape, Plin, BCP, BBVA)
        matches = re.findall(r'(?i)S/\s*([\d.,]+)', text)
        max_amount = 0.0
        
        for amt_str in matches:
            cleaned = PagoVozEngineEngine.clean_amount_string(amt_str)
            try:
                val = float(cleaned)
                if val > max_amount:
                    max_amount = val
            except ValueError:
                pass
                
        if max_amount > 0.0:
            return max_amount
            
        # 2. Respaldo regex numérico general (ej: "te envió 25.50 soles")
        matches_gen = re.findall(r'(\d+([.,]\d{1,2}))', text)
        for group in matches_gen:
            amt_str = group[0] if isinstance(group, tuple) else group
            cleaned = PagoVozEngineEngine.clean_amount_string(amt_str)
            try:
                val = float(cleaned)
                if val > max_amount:
                    max_amount = val
            except ValueError:
                pass
                
        return max_amount

    @staticmethod
    def speak_amount(cleaned_amount):
        """
        Formatea importes numéricos a habla humana en español peruano (ej: 10.50 -> 10 soles con 50 céntimos).
        """
        parts = cleaned_amount.split('.')
        try:
            soles = int(parts[0]) if parts[0] else 0
        except ValueError:
            soles = 0
            
        centimos = 0
        if len(parts) > 1:
            cent_str = (parts[1] + "0")[:2]
            try:
                centimos = int(cent_str)
            except ValueError:
                centimos = 0
                
        if soles > 0 and centimos > 0:
            str_soles = f"{soles} soles" if soles != 1 else "1 sol"
            str_centimos = f"{centimos} céntimos" if centimos != 1 else "1 céntimo"
            return f"{str_soles} con {str_centimos}"
        elif soles > 0:
            return f"{soles} soles" if soles != 1 else "1 sol"
        elif centimos > 0:
            return f"{centimos} céntimos" if centimos != 1 else "1 céntimo"
        else:
            return "cero soles"

    @staticmethod
    def format_yape_content(text):
        """
        Reemplaza montos tipo S/ 50.00 por su pronunciación hablada en el texto.
        """
        def _replace_match(match):
            amt_group = match.group(1) if match.group(1) else "0"
            cleaned = PagoVozEngineEngine.clean_amount_string(amt_group)
            return PagoVozEngineEngine.speak_amount(cleaned)
            
        formatted = re.sub(r'(?i)S/\.?\s*([\d.,]+)', _replace_match, text)
        return formatted

    @staticmethod
    def extract_security_code(text):
        match = re.search(r'(?i)(?:código de seguridad es:|cód\. de seguridad es:|código de seguridad|cód\. de seguridad|código)\s*:?\s*(\d+)', text)
        return match.group(1) if match else ""
