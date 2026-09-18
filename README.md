# GPT Ear Assistant

Point your phone camera at a paper with 1-7 questions, tap ONE button,
and the answers are spoken into your Bluetooth earbuds.
After that, the phone can go in your pocket.

## Features
- One photo -> GPT extracts ALL questions and answers each one
  (default rules: according to laws of Pakistan, 5-6 lines each)
- First answer plays automatically in your earbuds
- Phone can then be pocketed (background service keeps running)
- ANSWERS ARE SAVED ON THE PHONE: replay any time with ZERO internet
- LAST PHOTO IS SAVED TOO: if the network fails, retry later without
  re-taking the picture
- Earbud button controls:
    2 taps  = next question
    3 taps  = repeat current answer
    4 taps  = RETRY (re-send saved photo to ChatGPT)
    5 taps  = RESET (delete all saved answers + photo)
- In-app buttons: Replay saved answers / Reset
- Your API key and answer-rules prompt are saved in the app

## Requirements
- Android 8.0+ phone with camera
- Small Bluetooth earbuds with a button
- OpenAI API key (platform.openai.com)

## How to build & install
1. Install Android Studio (free): https://developer.android.com/studio
2. Open this folder as a project
3. Let Gradle sync finish
4. Enable Developer Options + USB debugging on your phone, plug it in
5. Press the green Run button ▶ -> app installs on your phone

## How to use
1. Pair your earbuds with the phone
2. Open the app, paste your OpenAI API key (saved once)
3. (Optional) edit the answer-rules prompt
4. Point camera at the question paper, tap "CAPTURE & ASK"
5. Put phone in pocket. Listen and control with earbud taps:
   - 2 taps -> next answer
   - 3 taps -> repeat
   - 4 taps -> retry if the network failed
   - 5 taps -> wipe everything when you're done

## Full offline behaviour
- Replaying answers (2/3 taps): NEVER needs internet
- Retry (4 taps): needs internet (re-sends the saved photo)
- Capture: needs internet
- Reset (5 taps): works offline

## Roadmap (next versions)
- [ ] Hardware trigger (BLE button) so even opening the app isn't needed
- [ ] Wake-word ("Hey assistant") capture with phone in pocket
- [ ] ESP32-CAM wearable lens streaming photos to the phone
- [ ] Urdu TTS voice option
