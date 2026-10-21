function Get-MediaryPython {
    # Windows 的商店占位程序可能存在于 PATH，却没有可运行的 Python；优先寻找实际解释器。
    foreach ($taskName in @('python', 'python3', 'py')) {
        $taskCommand = Get-Command $taskName -ErrorAction SilentlyContinue
        if ($taskCommand -and $taskCommand.Source -notmatch '[\\/]Microsoft[\\/]WindowsApps[\\/]') {
            return [pscustomobject]@{ Executable = $taskCommand.Source; Arguments = $(if ($taskName -eq 'py') { @('-3') } else { @() }) }
        }
    }
    throw 'Python 3 is required. Install Python with its launcher or add the interpreter to PATH.'
}
