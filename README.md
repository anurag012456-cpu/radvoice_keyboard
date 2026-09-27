# RadVoice Keyboard – radiology dictation keyboard for Android

Voice-first input method (IME). Works in any app: PACS/RIS web pages, WhatsApp, Google Docs, email.
Gboard does not accept third-party plugins, so this is a separate keyboard you flip to from Gboard's keyboard/globe key.

## Build (Android Studio, ~5 min)
1. Android Studio (Ladybug or newer) › Open › select this `RadVoiceKeyboard` folder. Let Gradle sync (it downloads AGP 8.7.3 / Kotlin 2.0.21).
2. Build › Build App Bundle(s)/APK(s) › Build APK(s).  Output: `app/build/outputs/apk/debug/app-debug.apk`
3. Copy to phone and install (allow "install unknown apps" for your file manager), or Run ▶ with the phone on USB debugging.

## First run
Open **RadVoice Keyboard** app →
1. Grant microphone permission
2. Enable RadVoice in keyboard settings
3. Switch keyboard to RadVoice
4. (Android 13+) Download offline speech model – keeps audio on the phone

## Use
- 🎤 start / ■ stop. Dictate continuously; long pauses are tolerated.
- Spoken punctuation: *full stop, comma, semicolon, colon mark, question mark, new line, new paragraph, open/close bracket, hyphen, slash, plus minus*. Plain "colon" stays as anatomy.
- ⌨ back to Gboard (long-press = keyboard picker) · ↶ undo last dictated phrase · ⌫ hold to repeat · **Teach** = correct the last phrase so it never recurs.

## What the text engine does
Corrections (built-in `assets/corrections.tsv` + your table) → numbers ("three point two" → 3.2) → units → dimensions (3.2 × 2.1 × 1.8 cm) →
spinal levels (L4 L5 → L4–L5) → canonical terms from `assets/radiology_lexicon.txt` (~3,200 terms: BI-RADS, T2-weighted, Doppler, Morison's pouch …) →
British/American spelling → punctuation and capitalisation.

Edit the two asset files and rebuild to extend the built-in lists; or add terms/corrections from the app without rebuilding.

## Privacy
The app has **no internet permission**. Audio goes only to the phone's speech service; choose on-device recognition to keep it on the phone.
If on-device is unavailable the Google recogniser is used (Google processes audio) – avoid dictating patient identifiers in that mode.
