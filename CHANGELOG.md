# Changelog

## [0.3.0](https://github.com/dchernykh1984/ChronometerAndroid/compare/chronometer-android-v0.2.0...chronometer-android-v0.3.0) (2026-07-06)


### Features

* add app launcher icon from the Windows chronometer ([766b983](https://github.com/dchernykh1984/ChronometerAndroid/commit/766b9832b4a676bb692e4f7282f93e3c30fc774a))


### Bug Fixes

* compact the main screen in landscape so the cutoff log stays visible ([2b70637](https://github.com/dchernykh1984/ChronometerAndroid/commit/2b70637a52416e63b72075cdb720274eb8a5c6d0))
* stop the app title and language chips from overflowing on narrow screens ([f0a8939](https://github.com/dchernykh1984/ChronometerAndroid/commit/f0a8939137026a7439d9ffc20f4333b65cf2d6ac))

## [0.2.0](https://github.com/dchernykh1984/ChronometerAndroid/compare/chronometer-android-v0.1.0...chronometer-android-v0.2.0) (2026-07-06)


### Features

* add digits/text number input type setting (default digits) ([9c12050](https://github.com/dchernykh1984/ChronometerAndroid/commit/9c120504140659f17b226bd410ded59dfe99694b))
* add finish-mode setting to record finish instead of nextLap ([24e0284](https://github.com/dchernykh1984/ChronometerAndroid/commit/24e0284835113e5d15f2f59596bbd9d77561001c))
* add foreground service with ongoing notification for active events ([4cb2db1](https://github.com/dchernykh1984/ChronometerAndroid/commit/4cb2db1ca74aa5e3a9e7a580b1d486db4ae7d7b2))
* add light/dark/system theme setting (default system) ([5916090](https://github.com/dchernykh1984/ChronometerAndroid/commit/5916090038d8dd69d9bbab1216c1fe9dbe8b9117))
* add New competition reset button with data folder size ([b6dad34](https://github.com/dchernykh1984/ChronometerAndroid/commit/b6dad343cea743b66d21965999843a78f3039757))
* implement offline chronometer (cutoffs, DSQ, settings, backup, HTTP upload) ([987091b](https://github.com/dchernykh1984/ChronometerAndroid/commit/987091bacdb13e657aecc6bda1198b61513cbc70))
* localize UI to English/Russian/Kazakh with in-app language switch ([7e04250](https://github.com/dchernykh1984/ChronometerAndroid/commit/7e0425064b836410fa0c9139d2eba3ae28dda4cc))
* make cutoff button large and DSQ button small and secondary ([f868b9f](https://github.com/dchernykh1984/ChronometerAndroid/commit/f868b9f779f62cd19e96113abb6015d0e73a67ff))
* prompt to save or discard settings when leaving with unsaved changes ([a36f5a3](https://github.com/dchernykh1984/ChronometerAndroid/commit/a36f5a3ef9dab90c044990c7df5a1efdeb79441b))
* record cutoff events (nextLap, DSQ) as number#time#event# ([a4d9e7d](https://github.com/dchernykh1984/ChronometerAndroid/commit/a4d9e7dfc613dcc86a8824389bfbb1b28273c6a5))
* rename event button to timing mode with keep-screen-on and DND hints ([77c55e5](https://github.com/dchernykh1984/ChronometerAndroid/commit/77c55e5efed36b2acad2be9f85a33a379b9ab744))
* scroll the cutoff log back to the top after each new cutoff ([53d9dfc](https://github.com/dchernykh1984/ChronometerAndroid/commit/53d9dfc691d70bf34da7ed73a1931967661a10b0))
* show upload and backup diagnostics on the main screen ([c9e09e5](https://github.com/dchernykh1984/ChronometerAndroid/commit/c9e09e5db153ddf1d63d4d37aeec2dea4285b307))


### Bug Fixes

* allow cleartext HTTP for the configured upload endpoint ([6ea8c54](https://github.com/dchernykh1984/ChronometerAndroid/commit/6ea8c54a1ba396322e02d04cd64502b6e16a6982))
* avoid null applicationContext crash when reading language in attachBaseContext ([24743fe](https://github.com/dchernykh1984/ChronometerAndroid/commit/24743fede0554c00ad453c470e95b0a953f2813d))
* defer auto-focus a frame so FocusRequester is initialized before use ([9f70bf2](https://github.com/dchernykh1984/ChronometerAndroid/commit/9f70bf2d365a2d838a704905b31809a024113e63))
* do not let an older failed upload mask a later successful one in the status ([a23e9af](https://github.com/dchernykh1984/ChronometerAndroid/commit/a23e9af84c575b289309ce76974bcfdbdd4a7fcc))
* keep state when new competition reset fails ([1ef895a](https://github.com/dchernykh1984/ChronometerAndroid/commit/1ef895a8543c5b5a4209349a1320b7aa2eaf315b))
* migrate Room schema 1-&gt;2 instead of dropping recorded cutoffs ([e9b7597](https://github.com/dchernykh1984/ChronometerAndroid/commit/e9b75976a5da294528543060cb1b3110e0cc2485))
* name backup snapshots by unique id and never overwrite on stamp collision ([8e852a2](https://github.com/dchernykh1984/ChronometerAndroid/commit/8e852a237d20f7f5ddea15fe94958ace6d576f41))
* normalize gradlew.bat line endings ([a1f88d2](https://github.com/dchernykh1984/ChronometerAndroid/commit/a1f88d2bcb097aa2d3ffe4ce539eb701f0133931))
* reject non-http(s) upload URLs in settings and the client ([d9c01ba](https://github.com/dchernykh1984/ChronometerAndroid/commit/d9c01baf35321b56b2ceb1040fbe2b728a9e8621))
* replace backup files atomically instead of a partial overwrite ([f380e3d](https://github.com/dchernykh1984/ChronometerAndroid/commit/f380e3d4467d91b17ab7d5384333364d6fa4ad5a))
* require a configured token and site before uploading ([abc1619](https://github.com/dchernykh1984/ChronometerAndroid/commit/abc1619af1b48bcf25a9afb6276d37edb95ed2b7))
* retry uploads on transient errors and give up on config errors ([5800391](https://github.com/dchernykh1984/ChronometerAndroid/commit/5800391041dba71d5a2ff469dbeebfffe6273aa3))
* stop mutating the process-wide default locale for UI localization ([0f4a519](https://github.com/dchernykh1984/ChronometerAndroid/commit/0f4a519c6518d235f2d4f2fd471fca693a11f616))
* upload already-recorded cutoffs when upload settings become ready ([1346daf](https://github.com/dchernykh1984/ChronometerAndroid/commit/1346daf1bad36dabad5b22dad01bbc65ada905fb))
