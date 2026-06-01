# fan_key_detect.py
# Run once to find the VK code that Fn+F8 generates on your system.
# No pip install needed -- uses only built-in ctypes + Windows API.
#
# Usage: python fan_key_detect.py
# Then press Fn+F8 (and any other keys you want to check).
# The VK code printed is what to set as FanToggleVK in conf/settings.properties.
# Close the window (or Ctrl+C) when done.
#
# Common VK codes: F1=112 F2=113 F3=114 F4=115 F5=116 F6=117 F7=118 F8=119
#                  F9=120 F10=121 F11=122 F12=123

import ctypes
from ctypes import wintypes
import sys

WH_KEYBOARD_LL = 13
WM_KEYDOWN = 0x0100

class KBDLLHOOKSTRUCT(ctypes.Structure):
    _fields_ = [
        ("vkCode",      wintypes.DWORD),
        ("scanCode",    wintypes.DWORD),
        ("flags",       wintypes.DWORD),
        ("time",        wintypes.DWORD),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    ]

user32 = ctypes.windll.user32
HOOKPROC = ctypes.WINFUNCTYPE(ctypes.c_long, ctypes.c_int, wintypes.WPARAM, wintypes.LPARAM)

_logfile = open("fan_key_output.txt", "w")

def on_key(nCode, wParam, lParam):
    if nCode >= 0 and wParam == WM_KEYDOWN:
        kb = ctypes.cast(lParam, ctypes.POINTER(KBDLLHOOKSTRUCT)).contents
        line = "VK code: {}   scan code: {}".format(kb.vkCode, kb.scanCode)
        print(line)
        _logfile.write(line + "\n")
        _logfile.flush()
    return user32.CallNextHookEx(None, nCode, wParam, lParam)

_hook_ref = HOOKPROC(on_key)
hHook = user32.SetWindowsHookExW(WH_KEYBOARD_LL, _hook_ref, None, 0)
if not hHook:
    print("Could not install hook. Try running as admin.")
    sys.exit(1)

print("Press Fn+F8 to see its VK code. Close this window when done.")
msg = wintypes.MSG()
try:
    while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) != 0:
        user32.TranslateMessage(ctypes.byref(msg))
        user32.DispatchMessageW(ctypes.byref(msg))
finally:
    user32.UnhookWindowsHookEx(hHook)
