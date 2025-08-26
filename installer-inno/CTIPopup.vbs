Dim objShell, objFSO, scriptDir, batPath
Set objShell = CreateObject("Wscript.Shell")
Set objFSO = CreateObject("Scripting.FileSystemObject")
scriptDir = objFSO.GetParentFolderName(WScript.ScriptFullName)
batPath = scriptDir & "\CTIPopup.bat"
objShell.Run """" & batPath & """", 0, False
