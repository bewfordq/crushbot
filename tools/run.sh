#!/usr/bin/env bash
# Runs the bot on the emulator for a while and prints its log.
#   tools/run.sh <seconds> [tune] [logfile]
set -u
SECS=${1:-30}
TUNE=${2-}
LOG=${3:-/tmp/crushbot-run.log}
ADB=${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb
# The accessibility service is switched off by a force-stop; make sure it is on.
on=$($ADB shell settings get secure enabled_accessibility_services)
case "$on" in
  *com.crushbot.app*) ;;
  null|"") $ADB shell settings put secure enabled_accessibility_services com.crushbot.app/com.crushbot.app.BotService ;;
  *) $ADB shell settings put secure enabled_accessibility_services "$on:com.crushbot.app/com.crushbot.app.BotService" ;;
esac
for i in $(seq 1 20); do $ADB shell dumpsys accessibility | grep -q "Crushbot" && break; sleep 1; done
$ADB logcat -c
$ADB logcat -s Crushbot:I AndroidRuntime:E -v time > "$LOG" &
LC=$!
$ADB shell am start -n com.crushbot.app/.MainActivity --ez start true --es tune "\"$TUNE\"" >/dev/null
sleep "$SECS"
$ADB shell am start -n com.crushbot.app/.MainActivity --ez stop true >/dev/null
sleep 2
kill $LC
cut -c19- "$LOG" | sed 's/^ *[0-9]* *[0-9]* I Crushbot: //; s/^I\/Crushbot( *[0-9]*): //'
