# shintaku

The app stems from my anxiety about what to study. i study one thing and worry about another. It eliminates decision fatigue on what to study. You input sleep metrics, it calculates biological cognitive capacity, you input syllabus, it assigns exact page counts based on real-time neural alignment. It learns your reading speed, it builds its own graphs, zero external dependencies. zero tracking.

An open source, privacy focussed, math model oracle android app that tells you what to study next.

## features

- Add list of books you need to study with how many pages they are, and how difficult they are (for example I made a manga tag you can use to read magazines which have difficulty rating of 1/10).
- Follow the oracle's order and complete the amount of pages it gave you, you could complete more pages and input study progress too.
- Retro terminalesque design: Inspired by Unix customizations found online.
- Lightweight: Consumes only 0.06% CPU and 85KB of RAM. After all, simple apps shouldn't need more—remember, the Apollo mission operated on a computer with around 4KB of RAM!
- Highly customizable: Offers many themes with option for custom fonts.


<div align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" alt="App Icon" width="45%">
</div>



## The algorithms

Shintaku operates on three math engines: circadian alignment, regression-based speed prediction, and a priority scoring matrix.

### 1. Epi-circadian engine

Humans do not have linear focus. Focus is a wave dictated by sleep quality and hours since wake (hsw).

Sleep quality ($Q$) is calculated using duration against an 8-hour target, penalized by daytime light exposure (if waking between 10am and 10pm, environmental light degrades rem/deep sleep quality):

$$Q = \min\left(\frac{D_{\text{actual}}}{8.0} \cdot A, 1.2\right)$$

where $A$ is the alignment penalty (0.75 for daytime sleepers, 1.0 for night sleepers).

Raw biological alertness ($A_{raw}$) uses an asymmetric bi-bimodal curve. it maps the primary post-wake peak and the secondary evening wind-down:

$$A_{raw} = 0.6 \cdot \sin\left(\frac{\pi \cdot \text{hsw}}{6}\right) + 0.4 \cdot \cos\left(\frac{\pi \cdot \text{hsw}}{12}\right)$$

final cognitive potential ($C_p$) is the product of sleep quality and raw alertness:

$$C_p = A_{raw} \cdot Q$$

### 2. Multivariate ordinary least squares (ols) regression

The oracle needs to assign a 45-minute sprint. to do this, it must predict how fast you read specific subjects at specific times of day. Brain.js is too bloated. shintaku uses a custom multivariate linear regression model built in 20 lines of vanilla js.

It maps historical data:

* $X_1$: hours since wake
* $X_2$: subject difficulty
* $Y$: seconds per page

It calculates the means ($\bar{x}$, $\bar{y}$) of your historical reading logs for a specific tag.

slope ($B_1$) is calculated via covariance and variance:

$$B_1 = \frac{\sum (X_i - \bar{x})(Y_i - \bar{y})}{\sum (X_i - \bar{x})^2}$$

y-intercept ($B_0$):

$$B_0 = \bar{y} - B_1\bar{x}$$

predicted speed for current session:

$$Y_{predicted} = B_0 + (B_1 \cdot \text{current hsw})$$

If data is scarce (cold start), it uses deterministic fallbacks (e.g., math = 170 secs/page, manga = 35 secs/page).

### 3. Oracle priority matrix

When consulted, the oracle scores every incomplete book in your library. highest score wins.

Urgency ($U$) scales as you approach completion:

$$U = 1 - \left(\frac{\text{pages completed}}{\text{total pages}}\right)$$

Difficulty alignment ($D$) matches the book's difficulty to your real-time brain capacity. hard math aligns with peak $C_p$. light reading aligns with circadian dips.

$$D = 1 - \left| C_p - \frac{\text{difficulty}}{10} \right|$$

total priority score ($P$):

$$P = (Importance \cdot 2.2) + (D \cdot 9) + (U \cdot 4)$$

Once a book is selected, the oracle calculates exact page assignment to hit a 45-minute (2700 seconds) target block:

$$\text{Pages} = \frac{2700}{Y_{predicted}}$$

Pages are clamped between 1 and 25 to prevent extreme assignments. user clicks start, stopwatch runs, logic repeats.



## Screenshots

<div align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" alt="Screenshot 1" width="75%">
</div>




## Licenses
Shintaku is being developed under the GPLv3 License.



## Follow the development

See my thought process and approach with other's opinions on telegram: <https://t.me/karuifoss>
This app has been primarily made on my phone with Acode editor with alpine linux terminal.

Github Issues are the fastest way to get in touch, for other means there's gitlab, bluesky, and my dev.to account, all linked in my account page on github and [homepage](https://ronynn.github.io).

If you like the app, don't forget to leave a star ⭐ here on github and let me know your suggestions!
