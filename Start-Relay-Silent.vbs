Set WshShell = CreateObject("WScript.Shell")
strPath = WshShell.CurrentDirectory
If InStr(strPath, "gsm relay") = 0 Then
    strPath = "C:\Users\Ahmed\Downloads\gsm relay test rebuild"
End If
WshShell.Run Chr(34) & strPath & "\Start-Relay-Auto.bat" & Chr(34), 0, False
Set WshShell = Nothing
