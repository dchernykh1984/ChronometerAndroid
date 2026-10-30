# Changelog

## [0.8.0](https://github.com/dchernykh1984/ChronometerAndroid/compare/chronometer-android-v0.7.0...chronometer-android-v0.8.0) (2026-10-30)


### Features

* **beam:** queue beam breaks until the referee gives each a number ([9216d88](https://github.com/dchernykh1984/ChronometerAndroid/commit/9216d8843ea023fa98a8bf5171bbdd46cc89b093))
* **chips:** list unknown chips and time them once the map knows them ([9786ac4](https://github.com/dchernykh1984/ChronometerAndroid/commit/9786ac45e2bf04b4e48fb7740ed28314bf53d032))
* **chips:** map chips to bibs from start.txt ([a432d38](https://github.com/dchernykh1984/ChronometerAndroid/commit/a432d38f314c1b3aa7fc0972f0c6d728aef3f3ef))
* **chips:** turn chip reads into cutoffs with a dead time ([d07d83b](https://github.com/dchernykh1984/ChronometerAndroid/commit/d07d83b3277171d5c2a81b7deda95f1a1cb8c247))
* **cutoffs:** start a new competition without the last one's chips and breaks ([38e97af](https://github.com/dchernykh1984/ChronometerAndroid/commit/38e97af682de9671ba1bac4c3160ec6da10c735d))
* **db:** store chip reads, passes and pending beam breaks ([bc689cc](https://github.com/dchernykh1984/ChronometerAndroid/commit/bc689ccbf547c29940246e3a278933625c624add))
* **site:** download start.txt from the site by a button ([4ff3654](https://github.com/dchernykh1984/ChronometerAndroid/commit/4ff36544535622f6b81c80fe1473a891da9e2ea3))
* **sources:** choose the cutoff source and its device in the settings ([ba5b4ed](https://github.com/dchernykh1984/ChronometerAndroid/commit/ba5b4ed51fe5fe86561c645c8b69a7630f38a76d))
* **sources:** define cutoff sources, their events and the devices ([4e3e8b6](https://github.com/dchernykh1984/ChronometerAndroid/commit/4e3e8b627aa2d2e06bcb7334902f169e07da8941))
* **sources:** restart a failing source after a growing pause ([28a7bea](https://github.com/dchernykh1984/ChronometerAndroid/commit/28a7bea738a05530b47849db29c0f56d579fd360))
* **sources:** run the chosen source while timing mode is on and show its state ([fa09dc3](https://github.com/dchernykh1984/ChronometerAndroid/commit/fa09dc3876e04e361ebf2d3f8dadc30c8f92b2cd))


### Bug Fixes

* **beam:** count the test beam's breaks on when the runner starts it again ([7b512af](https://github.com/dchernykh1984/ChronometerAndroid/commit/7b512af21b3ecb69e3a1d31d1078603fd93a4bc8))
* **beam:** give a second quick tap the break after the one the first tap took ([71355cb](https://github.com/dchernykh1984/ChronometerAndroid/commit/71355cb02c4e66230d65d7ae02927037851bdeb8))
* **beam:** give the number to the break the button showed, not one stored as the tap waited ([97b774b](https://github.com/dchernykh1984/ChronometerAndroid/commit/97b774b3374ff93bae4a0e37e1cf7d6e69e70d64))
* **chips:** count a start.txt the app may not read as unreadable, not as every batch failing ([9f11f53](https://github.com/dchernykh1984/ChronometerAndroid/commit/9f11f5321de4f1f2098b18ba1b3e2f0c4115dd70))
* **chips:** drop a byte order mark from start.txt before decoding, so a cp1251 line loses it too ([95e3a76](https://github.com/dchernykh1984/ChronometerAndroid/commit/95e3a76bea5d26a5965a441b0e8a47253ed9da66))
* **chips:** keep the map read before when a reload cannot read start.txt ([73be8e0](https://github.com/dchernykh1984/ChronometerAndroid/commit/73be8e0dc0c3388fde7daf5245a85c7c47af296e))
* **chips:** keep the map read before when a reload finds no start.txt for a moment ([d235505](https://github.com/dchernykh1984/ChronometerAndroid/commit/d23550504914f3ae9c4d57895fbca0897080525a))
* **chips:** log a map reload or discard that fails instead of ending the app ([1642870](https://github.com/dchernykh1984/ChronometerAndroid/commit/164287048b4aa9aecf8738e5fb78ad37800a7f8b))
* **chips:** read the settings for a batch once it holds the lock, not before it waits ([a2b7e96](https://github.com/dchernykh1984/ChronometerAndroid/commit/a2b7e96eb97ebf79e911ee1d6c7e7fc13feb7432))
* **chips:** show that the chips line opens the chips screen ([367548a](https://github.com/dchernykh1984/ChronometerAndroid/commit/367548a792d4e13410a23baa5b26a4b59cefd0b6))
* **chips:** use a new map only once the reads it binds are timed ([bf5151c](https://github.com/dchernykh1984/ChronometerAndroid/commit/bf5151c31d1a580d681e08385d9db58feaacb075))
* **chips:** use no map from the folder before when another one has no start.txt ([e86e805](https://github.com/dchernykh1984/ChronometerAndroid/commit/e86e805765d8c60d88a14d62cc9b9464a0582dae))
* **chips:** write a map reload's cutoffs out before refreshing the waiting chips ([26e86da](https://github.com/dchernykh1984/ChronometerAndroid/commit/26e86dadfc883e214da2e3e4ec94e3417ff3c678))
* **cutoffs:** clear the raw file warnings with the rest when a new competition starts ([e44d3ef](https://github.com/dchernykh1984/ChronometerAndroid/commit/e44d3ef31d3547dca249df9b92fc13734e73405a))
* **cutoffs:** keep a typed hash or line break out of the results line on every path ([80e1c5b](https://github.com/dchernykh1984/ChronometerAndroid/commit/80e1c5b795263209883c029bd086d13b105863c5))
* **cutoffs:** let nothing after a stored change fail it, so no batch is stored twice ([99ff821](https://github.com/dchernykh1984/ChronometerAndroid/commit/99ff82121de75754f77b83321733a562ee52eb21))
* **cutoffs:** log a file a refused new competition could not put back ([09d722e](https://github.com/dchernykh1984/ChronometerAndroid/commit/09d722e30e17b4f297b16123e4416b82710ecd7f))
* **cutoffs:** log an upload that cannot be queued, not show it as a file failure ([cb6ebad](https://github.com/dchernykh1984/ChronometerAndroid/commit/cb6ebade781480716510ec095d9613b8b9f27ec1))
* **cutoffs:** log what failed after a stored change, besides showing the file warning ([359f1f1](https://github.com/dchernykh1984/ChronometerAndroid/commit/359f1f13a6a00e76bec8e3103d264eb8b5ce6cd2))
* **cutoffs:** put a refused new competition's files back where they were ([87d36cc](https://github.com/dchernykh1984/ChronometerAndroid/commit/87d36cc2791417a25c969de6e5949715eab606b8))
* **cutoffs:** time a hand cutoff at the tap, not after the beam check waits for the lock ([260904e](https://github.com/dchernykh1984/ChronometerAndroid/commit/260904e1cd8038efcfadad235ee2ec4085861c89))
* **cutoffs:** undo a new competition whose rows cannot be cleared, files and results.txt too ([dd9e63d](https://github.com/dchernykh1984/ChronometerAndroid/commit/dd9e63d151ad319060f819970bbece3ff0f2d0d5))
* **i18n:** call cutoffs belgiler in the new Kazakh strings, as the app does ([6b4d4b0](https://github.com/dchernykh1984/ChronometerAndroid/commit/6b4d4b0608f506667e4944bc5cccf471eb746fc1))
* **i18n:** say in the referee's language what the app reports about a source or a download ([78de26c](https://github.com/dchernykh1984/ChronometerAndroid/commit/78de26ccc66c22b524d14d29453bcf8cbb61a2b5))
* **io:** start a raw append on a new line when the file ends in one cut short ([af8353c](https://github.com/dchernykh1984/ChronometerAndroid/commit/af8353ca7ae61aa4abc83b91e8f34f9f6705b027))
* **settings:** apply a language the download button saved, as Save does ([f4da929](https://github.com/dchernykh1984/ChronometerAndroid/commit/f4da929c67f52f4eb39990391e17c9f45a45cecb))
* **settings:** hold Save back for a dead time only where its field shows, storing none unfit ([4414878](https://github.com/dchernykh1984/ChronometerAndroid/commit/4414878c0ed37d743fcd1c2a7b78798acb755ae1))
* **settings:** leave a cleared dead time field empty, so 3 typed is not read as 30 ([cd8a5bd](https://github.com/dchernykh1984/ChronometerAndroid/commit/cd8a5bdef3d1cd34faf38e6d22b834f431cad5ce))
* **settings:** refuse a dead time out of its range instead of storing 1 s for a cleared field ([c68873d](https://github.com/dchernykh1984/ChronometerAndroid/commit/c68873d8cfd16a6fc0fb14ae53d61680d056bdde))
* **settings:** say the dead time's range beside its field ([2442400](https://github.com/dchernykh1984/ChronometerAndroid/commit/244240033e6711a380e84f84d3def689d2c11cd6))
* **site:** clear the last download's line when a new competition moves start.txt aside ([78526f5](https://github.com/dchernykh1984/ChronometerAndroid/commit/78526f56a8a922755bdcd6c5e18b535ed2650892))
* **site:** finish using a downloaded start.txt even when its screen is gone ([a7722f1](https://github.com/dchernykh1984/ChronometerAndroid/commit/a7722f19d49432ac94aa69a509b5075756f8512b))
* **site:** keep a list fetched before a new competition out of it ([911d64f](https://github.com/dchernykh1984/ChronometerAndroid/commit/911d64f16fad55ba1653dcfaad700be636db1e70))
* **site:** keep one download at a time across a new competition ([b724858](https://github.com/dchernykh1984/ChronometerAndroid/commit/b72485803e6cfd46db28407c3a1ed96d07b16d0f))
* **site:** mask the token in a download's error the way an upload's is, url-encoded too ([9c94c51](https://github.com/dchernykh1984/ChronometerAndroid/commit/9c94c515e523da6ddbfba7dfc8beff59ad9d9b05))
* **site:** refuse an answer the JSON parser fails on at its end instead of crashing ([db9f113](https://github.com/dchernykh1984/ChronometerAndroid/commit/db9f1138b763da9f597e52fef9d07f6c8c2e2d9f))
* **site:** report a download done only with the map of the start.txt it wrote ([d69caf9](https://github.com/dchernykh1984/ChronometerAndroid/commit/d69caf94490a8e1375c299e18e471bb08e7def42))
* **site:** say a download's map could not be used instead of ending the app ([ccc5803](https://github.com/dchernykh1984/ChronometerAndroid/commit/ccc58032945018ed68ef56a840e5f1ff6293f61e))
* **site:** write a downloaded start.txt and use it under the lock a new competition takes ([d29ec65](https://github.com/dchernykh1984/ChronometerAndroid/commit/d29ec659715b9a6adc3e1595249c83f6dfbb512f))
* **sources:** append raw lines under the lock a new competition moves their files under ([15aa770](https://github.com/dchernykh1984/ChronometerAndroid/commit/15aa770b134a14ed3a5936d363f8c2cf6fbda5d8))
* **sources:** clear what the last competition could not store when a new one starts ([4c71247](https://github.com/dchernykh1984/ChronometerAndroid/commit/4c71247bf02613433d45ad354bccc41eed9ab724))
* **sources:** close a run's channel only once the run has ended, keeping its last event ([fe27a45](https://github.com/dchernykh1984/ChronometerAndroid/commit/fe27a4518aeda65afa4bb1cb2829e279c973110e))
* **sources:** drop a batch failing the same way for minutes, so the events behind it are stored ([26ff8cc](https://github.com/dchernykh1984/ChronometerAndroid/commit/26ff8ccb7a00c87e4c48c83bd8770247407e4a99))
* **sources:** drop events taken before a new competition instead of storing them in it ([de6217f](https://github.com/dchernykh1984/ChronometerAndroid/commit/de6217fbf82e7ee2310c26ce567a80ebd074fbcb))
* **sources:** drop the last competition's events under the lock, where a new one starts ([b580bec](https://github.com/dchernykh1984/ChronometerAndroid/commit/b580bec7d3d2d6d24f6254073c4b9c09c103dc3e))
* **sources:** fail and restart a source that throws an error, not only an exception ([227aa47](https://github.com/dchernykh1984/ChronometerAndroid/commit/227aa4756d147479df450233d2c60d30667288e0))
* **sources:** keep a raw file's warning once it missed lines, until a new competition ([a4a6c6e](https://github.com/dchernykh1984/ChronometerAndroid/commit/a4a6c6e02bb276fa2109a4d878d28474c9a820bd))
* **sources:** keep a stopped run's late status from showing the source still running ([17dff11](https://github.com/dchernykh1984/ChronometerAndroid/commit/17dff11732bb5e73298d539ab9258da53fe25773))
* **sources:** keep the events a source sent when a store fails or the source is stopped ([ba5b165](https://github.com/dchernykh1984/ChronometerAndroid/commit/ba5b1650d89c0ccb02134f240be2173a924ff946))
* **sources:** leave a device's status reports out of the batches to store ([f5781ce](https://github.com/dchernykh1984/ChronometerAndroid/commit/f5781ce8d2597860f5f08fe6e2f0f8e8fff4ba9a))
* **sources:** let a store error go with the competition it failed for ([341d901](https://github.com/dchernykh1984/ChronometerAndroid/commit/341d9013f4e2042fe64413248a62fa3a5d648dc2))
* **sources:** restart a running source on save only when its device changed ([a28992a](https://github.com/dchernykh1984/ChronometerAndroid/commit/a28992a52c8feb420a2a7f32ab5f67ea2d011ae0))
* **sources:** retry only the kind of events that failed and drop a batch right after its last try ([c315dd2](https://github.com/dchernykh1984/ChronometerAndroid/commit/c315dd2da78cfa9f4f80e3e5f5fcc73baac4b09e))
* **sources:** show a store still failing from before a restart on the run after it ([c5f866e](https://github.com/dchernykh1984/ChronometerAndroid/commit/c5f866e8730a64cbac909487bae4c9d4ea36f951))
* **sources:** start the source once a timing-mode service, not on every start command ([980d539](https://github.com/dchernykh1984/ChronometerAndroid/commit/980d5396d4edaddc8c6723a83519c3916abfd0d9))
* **sources:** time an event its device did not time as it arrives, not as it is stored ([1b091af](https://github.com/dchernykh1984/ChronometerAndroid/commit/1b091af973f6f84aa811d45d8b681439f1d43e3a))
* **sources:** write a batch's raw lines first and once, so a batch Room cannot take is kept ([697446c](https://github.com/dchernykh1984/ChronometerAndroid/commit/697446c18b29b397ee24e4c967aa559b4f21fff8))


### Performance Improvements

* **chips:** keep the waiting chips in memory, read from Room once a map reload ([8e77384](https://github.com/dchernykh1984/ChronometerAndroid/commit/8e77384f43c3a5e3047b48ba0b0012593d20e9dc))
* **chips:** read a batch's passes once a bib, not once a read, under the lock ([1273e5b](https://github.com/dchernykh1984/ChronometerAndroid/commit/1273e5bc2ae5a6cbe88a653f60122d819443d9d7))
* **db:** group the unknown chips from the chip reads index alone ([891b0c2](https://github.com/dchernykh1984/ChronometerAndroid/commit/891b0c25b6a5dddcfc2adfd355c13dec8955dd67))

## [0.7.0](https://github.com/dchernykh1984/ChronometerAndroid/compare/chronometer-android-v0.6.2...chronometer-android-v0.7.0) (2026-10-22)


### Features

* **cutoffs:** delete entries with recovery snapshots ([0250ef7](https://github.com/dchernykh1984/ChronometerAndroid/commit/0250ef766457afe86997250506fb3dbc0376f4e1))

## [0.6.2](https://github.com/dchernykh1984/ChronometerAndroid/compare/chronometer-android-v0.6.1...chronometer-android-v0.6.2) (2026-10-22)


### Bug Fixes

* **upload:** count revisions in a Long, carrying on from the stored Int ([2162a18](https://github.com/dchernykh1984/ChronometerAndroid/commit/2162a18a6cce0278d34ebc84573a8052c68837b2))
* **upload:** keep counting revisions across a new competition ([baf3f4e](https://github.com/dchernykh1984/ChronometerAndroid/commit/baf3f4e02b2eb92d5c123048a45dd64de00809ba))

## [0.6.1](https://github.com/dchernykh1984/ChronometerAndroid/compare/chronometer-android-v0.6.0...chronometer-android-v0.6.1) (2026-10-21)


### Bug Fixes

* **upload:** show the reason for failed uploads ([9a9d1a2](https://github.com/dchernykh1984/ChronometerAndroid/commit/9a9d1a26f4d4264f86ad85e21c06e5fc9ef95263))

## [0.6.0](https://github.com/dchernykh1984/ChronometerAndroid/compare/chronometer-android-v0.5.1...chronometer-android-v0.6.0) (2026-08-27)


### Features

* edit a recorded cutoff's number and time from the log ([1972b28](https://github.com/dchernykh1984/ChronometerAndroid/commit/1972b281ebabf7ff0c85884f2c1016a08480c850))

## [0.5.1](https://github.com/dchernykh1984/ChronometerAndroid/compare/chronometer-android-v0.5.0...chronometer-android-v0.5.1) (2026-07-08)


### Bug Fixes

* keep the pending number and DSQ reason across the settings screen ([cac98d1](https://github.com/dchernykh1984/ChronometerAndroid/commit/cac98d1b61400ae1d8099521d3ecea0bdeadd6a2))

## [0.5.0](https://github.com/dchernykh1984/ChronometerAndroid/compare/chronometer-android-v0.4.0...chronometer-android-v0.5.0) (2026-07-08)


### Features

* record a disqualification reason as DSQ: reason ([81bf14c](https://github.com/dchernykh1984/ChronometerAndroid/commit/81bf14c2cbae304617192480fb60aa705668a860))

## [0.4.0](https://github.com/dchernykh1984/ChronometerAndroid/compare/chronometer-android-v0.3.0...chronometer-android-v0.4.0) (2026-07-07)


### Features

* lock phones to portrait, leave large screens unrestricted ([552b257](https://github.com/dchernykh1984/ChronometerAndroid/commit/552b25701ba64d6ec15b2769a9c9876320630bf2))

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
