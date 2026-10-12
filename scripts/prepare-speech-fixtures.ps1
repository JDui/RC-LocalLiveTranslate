#requires -Version 7.0
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$root = Split-Path -Parent $PSScriptRoot
$directory = Join-Path $root '.tools/models/speech/tts-fixtures'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$cases = @(
    @{ id = 'en'; file = 'en.wav'; voice = 'Microsoft Zira Desktop'; referenceText = 'Today we are testing local speech recognition. The meeting starts at nine in the morning.' },
    @{ id = 'zh'; file = 'zh.wav'; voice = 'Microsoft Huihui Desktop'; referenceText = '今天我们测试本地语音识别。会议将在明天上午九点开始。' },
    @{ id = 'ja'; file = 'ja.wav'; voice = 'Microsoft Haruka Desktop'; referenceText = '今日は音声認識をテストします。会議は明日の午前九時に始まります。' }
)
$synth = [System.Speech.Synthesis.SpeechSynthesizer]::new()
try {
    $installed = @($synth.GetInstalledVoices() | Where-Object { $_.Enabled } | ForEach-Object { $_.VoiceInfo.Name })
    foreach ($case in $cases) { if ($case.voice -notin $installed) { throw "Missing local Windows voice: $($case.voice)" } }
    $format = [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000,
        [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
    foreach ($case in $cases) {
        $output = Join-Path $directory $case.file
        if (Test-Path -LiteralPath $output) { throw "Refusing to overwrite speech fixture: $output" }
        $partial = Join-Path $directory "tts-$([Guid]::NewGuid().ToString('N')).wav"
        try {
            $synth.SelectVoice($case.voice)
            $synth.SetOutputToWaveFile($partial, $format)
            $synth.Speak($case.referenceText)
            $synth.SetOutputToNull()
            Move-Item -LiteralPath $partial -Destination $output
        } finally {
            $synth.SetOutputToNull()
            if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial }
        }
    }
    $manifest = @{ provenance = 'HOST_WINDOWS_LOCAL_TTS'; sampleRate = 16000; channels = 1; bitsPerSample = 16;
        purpose = 'Local evaluation only. Original test text, installed Windows voices, no microphone or downloaded public recordings.';
        redistribution = 'Not distributed in Git, APK or Release. Voice software remains subject to its installed Windows terms.';
        cases = $cases }
    [IO.File]::WriteAllText((Join-Path $directory 'manifest.json'), ($manifest | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    Write-Output "Prepared 3 local TTS fixtures: $directory"
} finally { $synth.Dispose() }
