param(
    [Parameter(Mandatory = $true)][string]$Type,
    [Parameter(Mandatory = $true)][string]$Message
)

# Windows toast via WinRT, no third-party modules. AppId pins to the registered
# PowerShell AUMID so the toast is accepted without installing a package.
try {
    [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
    $template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
    $texts = $template.GetElementsByTagName("text")
    $null = $texts.Item(0).AppendChild($template.CreateTextNode("TaskQueue/$Type"))
    $null = $texts.Item(1).AppendChild($template.CreateTextNode($Message))
    $toast = [Windows.UI.Notifications.ToastNotification]::new($template)
    [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\powershell.exe").Show($toast)
    exit 0
} catch {
    exit 1
}
