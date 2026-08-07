<div align="center">

<img src="metadata/en-US/images/icon.png" width="160" height="160" alt="Booming Music icon">

# 🎵 Booming Music

### Modern design. Pure sound. Fully yours.

[![Latest Release](https://img.shields.io/github/v/release/LuoRi-001/BooMingMusic?style=for-the-badge&label=Release&logo=github)](https://github.com/LuoRi-001/BooMingMusic/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/LuoRi-001/BooMingMusic/total?style=for-the-badge&logo=github&label=Downloads)](https://github.com/LuoRi-001/BooMingMusic/releases)
[![License: GPL v3](https://img.shields.io/github/license/LuoRi-001/BooMingMusic?style=for-the-badge&color=orange&label=License&logo=gnu)](LICENSE.txt)

</div>

## 🗂️ Table of Contents

- [✨ Key Features](#-key-features)
- [📸 Screenshots](#-screenshots)
- [📥 Download & Install](#-download--install)
- [💻 Tech Stack](#-tech-stack)
- [🧩 Roadmap](#-roadmap)
- [🤝 Contributing](#-contributing)
- [⚖️ License](#-license)

## ✨ Key Features

- 🎼 **Automatic Lyrics Download & Editing** – Automatically fetch, sync, and edit lyrics with ease.
- 💬 **Word-by-Word Synced Lyrics** – Enjoy immersive real-time lyric playback with word-level timing.
- 🌍 **Translated Lyrics Support** – Display dual-language lyrics via TTML or LRC with translations.
- 🔊 **Built-in Equalizer** – Powerful EQ with up to 15 fully configurable bands and customizable profiles.
- 🎧 **AutoEq Support** – Import professionally tuned headphone correction profiles for the most accurate sound possible.
- 🔄 **Gapless Playback** – Smooth transitions between songs with zero interruption.
- 🧠 **Smart Playlists** – Auto-generated lists like *Recently Played*, *Most Played*, and *History*.
- 📈 **Native Scrobbling** – Seamlessly sync your listening history with **Last.fm** and **ListenBrainz**.
- 🎧 **Bluetooth & Headset Controls** – Manage playback easily via connected devices.
- 🚗 **Android Auto Integration** – Full hands-free experience on the road.
- 🎨 **Material You Design** – Dynamic theming for a modern and personal interface.
- 📂 **Folder Browsing** – Play songs directly from any folder.
- ⏰ **Sleep Timer** – Automatically stop playback after a set time.
- 🧩 **Widgets** – Lock screen and home screen controls for quick access.
- 🔖 **Tag Editor** – Edit song metadata such as title, artist, and album info.
- 🔉 **ReplayGain Support** – Maintain consistent volume across all tracks.
- 🖼️ **Automatic Artist Images** – Download artist artwork for a polished library look.
- 🚫 **Library Filtering** – Easily exclude or include folders with blacklist/whitelist options.

## 📸 Screenshots

<div align="center">
<table>
<tr>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/1.jpg" alt="For You" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/2.jpg" alt="Songs" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/3.jpg" alt="Albums" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/4.jpg" alt="Album View" width="180"/></td>
</tr>
<tr>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/5.jpg" alt="Search" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/6.jpg" alt="Normal" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/7.jpg" alt="Full" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/8.jpg" alt="Gradient" width="180"/></td>
</tr>
<tr>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/9.jpg" alt="Plain" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/10.jpg" alt="M3" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/11.jpg" alt="Expressive" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/12.jpg" alt="Peek" width="180"/></td>
</tr>
</table>
</div>

## 📥 Download & Install

<div align="center">

|                                                                                   Source                                                                                    | Details                                   |
|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|:------------------------------------------|
|                      [<img src="assets/badge-github.png" alt="GitHub Releases" height="35">](https://github.com/LuoRi-001/BooMingMusic/releases/latest)                       | Direct APK download                       |

</div>

## 💻 Tech Stack

| Layer                   | Technology                                                     |
|:------------------------|:---------------------------------------------------------------|
| 🎧 Audio Engine         | [Media3 ExoPlayer](https://developer.android.com/media/media3) |
| 🧱 Architecture         | MVVM + Repository Pattern                                      |
| 💾 Persistence          | Room Database                                                  |
| ⚙️ Dependency Injection | [Koin](https://insert-koin.io/)                                |
| 🧵 Async                | Kotlin Coroutines & Flow                                       |
| 🧩 UI                   | Android Views + Jetpack Compose (hybrid)                       |
| 🖼️ Image Loading       | [Coil](https://coil-kt.github.io/coil/)                        |
| 🎨 Design               | Material 3 / Material You                                      |
| 🗣️ Language            | Kotlin                                                         |

## 🧩 Roadmap

- [ ] 📦 Independent library scanner (no MediaStore dependency)
- [ ] 🎵 Multi-artist support (split & index properly)
- [ ] 🎧 Improved genre handling
- [ ] 🔁 Last.fm integration (import/export playback data)
- [ ] 💿 Enhanced artist pages (separate albums and singles visually)
- [ ] 🌐 Jellyfin & Navidrome integration

## 🤝 Contributing

Booming Music is open-source — contributions are **always welcome!**
Check the [Contributing Guide](CONTRIBUTING.md) for details.

If you enjoy the app or want to support its development, give the repo a ⭐ — it really helps!
You can also:
- Open issues
- Submit pull requests
- Suggest new ideas

## ⚖️ License

```
GNU General Public License - Version 3
```

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
```

---

<p align="center"><a href="#readme">⬆️ Back to top</a></p>
