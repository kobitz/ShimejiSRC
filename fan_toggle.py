# fan_toggle.py <scancode>
# Sends a key press+release by hardware scan code via Windows SendInput.
# Called by FanController.java. Must live next to Shimeji-ee.jar.
# No pip install needed -- uses only built-in ctypes.

import ctypes, sys
from ctypes import wintypes

INPUT_KEYBOARD     = 1
KEYEVENTF_SCANCODE = 0x0008
KEYEVENTF_KEYUP    = 0x0002

class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk",         wintypes.WORD),
        ("wScan",       wintypes.WORD),
        ("dwFlags",     wintypes.DWORD),
        ("time",        wintypes.DWORD),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    ]

class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx",          wintypes.LONG),
        ("dy",          wintypes.LONG),
        ("mouseData",   wintypes.DWORD),
        ("dwFlags",     wintypes.DWORD),
        ("time",        wintypes.DWORD),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    ]

class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg",    wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]

class _INPUT_UNION(ctypes.Union):
    _fields_ = [
        ("mi", MOUSEINPUT),
        ("ki", KEYBDINPUT),
        ("hi", HARDWAREINPUT),
    ]

class INPUT(ctypes.Structure):
    _fields_ = [
        ("type",   wintypes.DWORD),
        ("_input", _INPUT_UNION),
    ]

scan   = int(sys.argv[1]) if len(sys.argv) > 1 else 61
user32 = ctypes.windll.user32
sz     = ctypes.sizeof(INPUT)

def send(scan, flags):
    inp = INPUT()
    inp.type = INPUT_KEYBOARD
    inp._input.ki.wVk    = 0
    inp._input.ki.wScan  = scan
    inp._input.ki.dwFlags = flags
    inp._input.ki.time   = 0
    user32.SendInput(1, ctypes.byref(inp), sz)

send(scan, KEYEVENTF_SCANCODE)
ctypes.windll.kernel32.Sleep(50)
send(scan, KEYEVENTF_SCANCODE | KEYEVENTF_KEYUP)
