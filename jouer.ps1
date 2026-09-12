<#
.SYNOPSIS
    Construit ce qu'il faut et lance le jeu.

.DESCRIPTION
    Trois choses que ce script fait a votre place, parce que les oublier coute
    a chaque fois une session de confusion :

    1. IL RECONSTRUIT `:engine:jar`. Le classpath d'execution designe
       `engine/build/libs/engine-*.jar`, pas les classes : une texture, un
       `.skin` ou un `.ui` modifie n'arrive donc JAMAIS dans le jeu tant que ce
       jar n'est pas refait. Gradle est incremental, ca ne coute rien quand rien
       n'a bouge.

    2. IL PASSE LE CLASSPATH PAR UN FICHIER D'ARGUMENTS. Il compte plus de cent
       entrees et depasserait la limite de 32 ko de la ligne de commande
       Windows. Dans un tel fichier la JVM traite l'antislash comme une
       echappement : ils sont donc doubles.

    3. IL REPARE `writeSaveGamesEnabled`. Ce n'est pas un drapeau par
       execution : `--no-save-games` le persiste a `false`, et tout lancement
       ulterieur meurt dans « Registering World Systems... ». Rien dans
       l'interface ne le remet.

.EXAMPLE
    .\jouer.ps1
    Construit si besoin, puis lance.

.EXAMPLE
    .\jouer.ps1 -SansBuild
    Relance tout de suite, sans repasser par Gradle.

.EXAMPLE
    .\jouer.ps1 -DernierePartie -Overlay
    Reprend la derniere partie, avec la surcouche de diagnostic (F3).
#>
param(
    # Repart de zero : `clean` avant de construire.
    [switch]$Clean,
    # Saute Gradle et lance ce qui est deja construit.
    [switch]$SansBuild,
    # Charge directement la derniere partie jouee.
    [switch]$DernierePartie,
    # Allume la surcouche de diagnostic (F3) pour cette execution et les suivantes.
    [switch]$Overlay,
    # JDK 17 a utiliser, si celui du systeme ne convient pas.
    [string]$Java,
    # Tout ce qui suit est transmis tel quel au jeu.
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Reste
)

$ErrorActionPreference = 'Stop'
$racine = $PSScriptRoot
Set-Location $racine

# --- le JDK ---------------------------------------------------------------
# Terasology veut Java 17. On prend, dans l'ordre : ce qui est demande ici,
# TERA_JAVA_HOME, JAVA_HOME, puis l'installation Microsoft par defaut.
$candidats = @(
    $Java,
    $env:TERA_JAVA_HOME,
    $env:JAVA_HOME,
    'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
) | Where-Object { $_ }

$jdk = $candidats | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
if (-not $jdk) {
    throw ("Aucun JDK trouve. Essayes : " + ($candidats -join ', ') +
           ". Indiquez-en un avec -Java <chemin>, ou posez TERA_JAVA_HOME.")
}
$javaExe = Join-Path $jdk 'bin\java.exe'
$env:JAVA_HOME = $jdk

$version = (& $javaExe -version 2>&1 | Select-Object -First 1)
Write-Host "JDK   $jdk"
Write-Host "      $version"

# --- construction ---------------------------------------------------------
$initScript = Join-Path $racine '.claude\skills\run-terasology\dump-classpath.init.gradle'
$classpathFile = Join-Path $racine 'build\run-classpath.txt'

if (-not $SansBuild) {
    $taches = @()
    if ($Clean) { $taches += 'clean' }
    # `:engine:jar` d'abord : c'est lui qui embarque les ressources modifiees.
    $taches += @(':engine:jar', ':facades:PC:dumpRunSpec', ':extractNatives')

    Write-Host "Build  $($taches -join ' ')"
    & (Join-Path $racine 'gradlew.bat') '--console=plain' '-I' $initScript @taches
    if ($LASTEXITCODE -ne 0) { throw "La construction a echoue (code $LASTEXITCODE)." }
}

if (-not (Test-Path $classpathFile)) {
    throw "Pas de classpath dans build\run-classpath.txt : relancez sans -SansBuild."
}

# --- fichier d'arguments --------------------------------------------------
$classpath = (Get-Content $classpathFile -Raw).Trim()
$argFile = Join-Path $racine 'build\run-args.txt'
$contenu = '-cp "' + $classpath.Replace('\', '\\') + '"' + [Environment]::NewLine
[System.IO.File]::WriteAllText($argFile, $contenu, (New-Object System.Text.UTF8Encoding $false))
Write-Host ("Classpath  {0} entrees" -f ($classpath.Split(';').Count))

# --- la sauvegarde bloquee ------------------------------------------------
$configSysteme = Join-Path $racine 'configs\engine\org.terasology.engine.config.SystemConfig.cfg'
if (Test-Path $configSysteme) {
    try {
        $brut = (Get-Content $configSysteme -Raw)
        $cfg = if ([string]::IsNullOrWhiteSpace($brut)) { [pscustomobject]@{} } else { $brut | ConvertFrom-Json }
    } catch {
        $cfg = $null
    }
    if ($cfg -and $cfg.PSObject.Properties.Name -contains 'writeSaveGamesEnabled' -and
        $cfg.writeSaveGamesEnabled -eq $false) {
        $cfg.PSObject.Properties.Remove('writeSaveGamesEnabled')
        $cfg | ConvertTo-Json -Compress | Set-Content $configSysteme -NoNewline
        Write-Host "Repare : writeSaveGamesEnabled etait a false, aucun monde n'aurait charge."
    }
}

if ($Overlay) {
    $dossier = Split-Path $configSysteme -Parent
    if (-not (Test-Path $dossier)) { New-Item -ItemType Directory -Force $dossier | Out-Null }
    $brut = if (Test-Path $configSysteme) { (Get-Content $configSysteme -Raw) } else { '' }
    $cfg = if ([string]::IsNullOrWhiteSpace($brut)) { [pscustomobject]@{} } else { $brut | ConvertFrom-Json }
    $cfg | Add-Member -NotePropertyName 'debugEnabled' -NotePropertyValue $true -Force
    $cfg | ConvertTo-Json -Compress | Set-Content $configSysteme -NoNewline
    Write-Host "Surcouche de diagnostic allumee (F3)."
}

# --- lancement ------------------------------------------------------------
$arguments = @(
    '-Xmx768M',
    '-XX:MaxDirectMemorySize=512M',
    ('@' + $argFile),
    'org.terasology.engine.Terasology',
    '--homedir=.',
    '--no-splash',
    '--no-crash-report'
)
if ($DernierePartie) { $arguments += '--load-last-game' }
if ($Reste) { $arguments += $Reste }

Write-Host "Lancement..."
& $javaExe @arguments
exit $LASTEXITCODE
