# NoDriver4j Scripting Guide

This guide covers how to create automation scripts using NoDriver4j, including all available Page interactions, configuration options, and best practices.

---

## Table of Contents

1. [Script Structure](#script-structure)
2. [Quick Start Example](#quick-start-example)
3. [BrowserManager Configuration](#browsermanager-configuration)
4. [Page Interactions Reference](#page-interactions-reference)
    - [Navigation](#navigation)
    - [Element Queries (XPath)](#element-queries-xpath)
    - [Waiting](#waiting)
    - [Mouse Interactions](#mouse-interactions)
    - [Keyboard Interactions](#keyboard-interactions)
    - [Scrolling](#scrolling)
    - [JavaScript Execution](#javascript-execution)
    - [Screenshots](#screenshots)
    - [Cookies](#cookies)
5. [InteractionOptions Configuration](#interactionoptions-configuration)
6. [Multi-Page Handling](#multi-page-handling)
7. [Error Handling](#error-handling)
8. [Script Examples](#script-examples)

---

## Script Structure

All automation scripts should follow this general structure:

```java
package org.nodriver4j.scripts;

import org.nodriver4j.core.*;
import java.util.concurrent.TimeoutException;

/**
 * Brief description of what this script does.
 */
public class MyScript {

    // Constants for timeouts, selectors, URLs, etc.
    private static final int PAGE_LOAD_TIMEOUT_MS = 30000;
    private static final String LOGIN_BUTTON_XPATH = "//button[@id='login']";

    // Dependencies
    private final Page page;

    /**
     * Constructor - inject the Page dependency.
     * Scripts should NOT create their own Browser instances.
     */
    public MyScript(Page page) {
        this.page = page;
    }

    /**
     * Main execution method.
     * @return Result object or void depending on script purpose
     */
    public MyResult execute() {
        // 1. Navigate to target
        // 2. Perform interactions
        // 3. Collect/return results
    }

    /**
     * Result class (if needed)
     */
    public static class MyResult {
        // Result fields
    }
}
```

### Design Principles

1. **Single Responsibility**: Each script should do ONE thing well
2. **Dependency Injection**: Accept `Page` via constructor, don't create browsers
3. **Immutable Results**: Return result objects, don't modify external state
4. **Idempotent When Possible**: Running twice should be safe
5. **Graceful Degradation**: Handle partial failures, return warnings

---

## Quick Start Example

```java
import org.nodriver4j.core.*;

public class QuickStartDemo {
    public static void main(String[] args) throws Exception {
        // 1. Create BrowserManager
        BrowserManager manager = BrowserManager.builder()
                .executablePath("/path/to/chrome")
                .proxyEnabled(false)
                .build();

        // 2. Use try-with-resources for automatic cleanup
        try (Browser browser = manager.createSession()) {
            Page page = browser.getPage();

            // 3. Navigate
            page.navigate("https://example.com");

            // 4. Interact
            page.click("//a[contains(text(), 'More information')]");

            // 5. Extract data
            String title = page.getTitle();
            System.out.println("Page title: " + title);

        } finally {
            manager.shutdown();
        }
    }
}
```

---

## BrowserManager Configuration

```java
BrowserManager manager = BrowserManager.builder()
        // REQUIRED
        .executablePath("/path/to/chrome")

        // Thread pool (default: auto-detect CPU cores)
        .threadCount(4)

        // Port range for CDP (default: 9222-9621)
        .portRange(9222, 9321)

        // Fingerprint spoofing (default: true)
        .fingerprintEnabled(true)

        // Profile warming - visits sites to collect cookies (default: false)
        .warmProfile(true)

        // Headless mode (default: false)
        .headless(false)

        // WebRTC leak prevention (default: "disable_non_proxied_udp")
        .webrtcPolicy("disable_non_proxied_udp")

        // Proxy from environment file (default: true)
        .proxyEnabled(true)

        // Human-like interaction timing (default: InteractionOptions.defaults())
        .interactionOptions(InteractionOptions.defaults())

        .build();
```

### Usage Patterns

```java
// Pattern 1: Single browser with manual control
try (Browser browser = manager.createSession()) {
    Page page = browser.getPage();
    // ... your automation code
}

// Pattern 2: Multiple browsers with parallel warming
List<Browser> browsers = manager.createSessions(5);
// All 5 browsers are launched and warmed in parallel
for (Browser browser : browsers) {
    // Use each browser
}
browsers.forEach(Browser::close);

// Pattern 3: Task submission (auto browser lifecycle)
Future<String> result = manager.submitPage(page -> {
    page.navigate("https://example.com");
    return page.getTitle();
});
String title = result.get();
```

---

## Page Interactions Reference

### Navigation

| Method | Description |
|--------|-------------|
| `navigate(String url)` | Navigate to URL, wait for load (30s timeout) |
| `navigate(String url, int timeoutMs)` | Navigate with custom timeout |
| `reload()` | Reload current page |
| `reload(boolean ignoreCache, int timeoutMs)` | Reload with options |
| `goBack()` | Navigate back in history |
| `goForward()` | Navigate forward in history |
| `getCurrentUrl()` | Get current page URL |
| `getTitle()` | Get page title |

```java
// Basic navigation
page.navigate("https://example.com");

// With custom timeout
page.navigate("https://slow-site.com", 60000);

// Get current state
String url = page.getCurrentUrl();
String title = page.getTitle();

// History navigation
page.goBack();
page.goForward();

// Reload
page.reload();
page.reload(true, 30000); // Ignore cache
```

---

### Element Queries (XPath)

All element queries use **XPath selectors**.

| Method | Description | Returns |
|--------|-------------|---------|
| `querySelector(String xpath)` | Find first matching element | `BoundingBox` or `null` |
| `querySelector(String xpath, int timeoutMs)` | Find with custom timeout | `BoundingBox` or `null` |
| `querySelectorAll(String xpath)` | Find all matching elements | `List<BoundingBox>` |
| `exists(String xpath)` | Check if element exists | `boolean` |
| `isVisible(String xpath)` | Check if element is visible | `boolean` |
| `getText(String xpath)` | Get element's inner text | `String` or `null` |
| `getAttribute(String xpath, String attr)` | Get attribute value | `String` or `null` |
| `getValue(String xpath)` | Get input element's value | `String` or `null` |

```java
// Find element (returns bounding box for click targeting)
BoundingBox button = page.querySelector("//button[@id='submit']");

// Find all elements
List<BoundingBox> items = page.querySelectorAll("//li[@class='item']");

// Check existence
if (page.exists("//div[@class='error']")) {
    System.out.println("Error message present!");
}

// Check visibility
boolean isVisible = page.isVisible("//div[@id='modal']");

// Extract text
String heading = page.getText("//h1");
String price = page.getText("//span[@class='price']");

// Get attributes
String href = page.getAttribute("//a[@id='link']", "href");
String dataId = page.getAttribute("//div[@class='card']", "data-id");

// Get input value
String email = page.getValue("//input[@name='email']");
```

#### XPath Tips

```java
// By ID
"//button[@id='submit']"

// By class (exact)
"//div[@class='container']"

// By class (contains)
"//div[contains(@class, 'btn')]"

// By text (exact)
"//button[text()='Click Me']"

// By text (contains)
"//a[contains(text(), 'Learn More')]"

// By attribute
"//input[@type='email']"
"//div[@data-testid='header']"

// Nth element
"(//li[@class='item'])[1]"  // First item
"(//li[@class='item'])[last()]"  // Last item

// Parent/ancestor
"//span[@class='icon']/parent::button"
"//td[text()='Price']/following-sibling::td"

// Multiple conditions
"//input[@type='text' and @name='username']"
"//button[@class='btn' or @class='button']"
```

---

### Waiting

| Method | Description |
|--------|-------------|
| `waitForSelector(String xpath)` | Wait for element to appear |
| `waitForSelector(String xpath, int timeoutMs)` | Wait with custom timeout |
| `waitForSelectorHidden(String xpath)` | Wait for element to disappear |
| `waitForSelectorHidden(String xpath, int timeoutMs)` | Wait hidden with timeout |
| `waitForNavigation()` | Wait for page navigation event |
| `waitForNavigation(int timeoutMs)` | Wait for navigation with timeout |
| `waitForNetworkIdle(int idleTimeMs)` | Wait for network to settle |
| `waitForNetworkIdle(int idleTimeMs, int timeoutMs)` | Network idle with timeout |

```java
// Wait for element to appear (uses default timeout from InteractionOptions)
BoundingBox modal = page.waitForSelector("//div[@class='modal']");

// Wait with custom timeout
BoundingBox result = page.waitForSelector("//div[@id='result']", 60000);

// Wait for loading spinner to disappear
page.waitForSelectorHidden("//div[@class='spinner']");

// Wait for navigation after clicking a link
page.click("//a[@href='/dashboard']");
page.waitForNavigation();

// Wait for AJAX to complete
page.click("//button[@id='load-more']");
page.waitForNetworkIdle(2000); // 2 seconds of no network activity
```

---

### Mouse Interactions

| Method | Description |
|--------|-------------|
| `click(String xpath)` | Click element with human-like movement |
| `clickAt(double x, double y)` | Click at specific coordinates |
| `clickAtBox(BoundingBox box)` | Click within a bounding box |
| `hover(String xpath)` | Hover over element |

```java
// Click element (human-like mouse movement + timing)
page.click("//button[@id='submit']");

// Click at coordinates
page.clickAt(100.0, 200.0);

// Click within a bounding box (random point inside)
BoundingBox box = page.querySelector("//div[@class='target']");
if (box != null) {
    page.clickAtBox(box);
}

// Hover to trigger dropdown/tooltip
page.hover("//div[@class='dropdown-trigger']");
Thread.sleep(500); // Let dropdown appear
page.click("//a[@class='dropdown-item']");
```

#### Mouse Movement Behavior

Mouse clicks include realistic human-like behavior:

1. **Path Generation**: Bezier curve movement from current position to target
2. **Overshoot**: For distant targets, may overshoot and correct
3. **Jitter**: Micro-tremor simulation for natural movement
4. **Click Position**: Random point within element (biased toward center)
5. **Timing**: Pre-click hesitation + realistic hold duration

Configure via `InteractionOptions` (see below).

---

### Keyboard Interactions

| Method | Description |
|--------|-------------|
| `type(String text)` | Type text with human-like timing |
| `type(String xpath, String text)` | Click element then type |
| `clear(String xpath)` | Clear input field (Ctrl+A, Backspace) |
| `focus(String xpath)` | Focus an element |
| `select(String xpath, String value)` | Select dropdown option |
| `pressKey(String key, boolean ctrl, boolean alt, boolean shift)` | Press key with modifiers |

```java
// Type into focused element
page.click("//input[@name='search']");
page.type("search query");

// Click and type in one call
page.type("//input[@name='email']", "user@example.com");

// Clear existing content first
page.clear("//input[@name='username']");
page.type("newusername");

// Select dropdown
page.select("//select[@name='country']", "US");

// Press special keys
page.pressKey("Enter", false, false, false);       // Enter
page.pressKey("Tab", false, false, false);         // Tab
page.pressKey("a", true, false, false);            // Ctrl+A (select all)
page.pressKey("c", true, false, false);            // Ctrl+C (copy)
page.pressKey("v", true, false, false);            // Ctrl+V (paste)
page.pressKey("Escape", false, false, false);      // Escape

// Focus without clicking
page.focus("//input[@id='search']");
```

#### Typing Behavior

Typing includes human-like patterns:

1. **Variable Speed**: Keystroke delays vary naturally
2. **Context-Aware**: Common bigrams (th, he, in) typed faster
3. **Thinking Pauses**: Occasional pauses while "thinking"
4. **Post-Punctuation**: Slight pause after periods, commas

Configure via `InteractionOptions` (see below).

---

### Scrolling

| Method | Description |
|--------|-------------|
| `scrollBy(int deltaX, int deltaY)` | Scroll relative amount |
| `scrollTo(int x, int y)` | Scroll to absolute position |
| `scrollIntoView(String xpath)` | Scroll element into viewport |
| `scrollToTop()` | Scroll to page top |
| `scrollToBottom()` | Scroll to page bottom |

```java
// Scroll down 500 pixels
page.scrollBy(0, 500);

// Scroll right 200 pixels
page.scrollBy(200, 0);

// Scroll to specific position
page.scrollTo(0, 1000);

// Scroll element into view before interacting
page.scrollIntoView("//button[@id='footer-btn']");
page.click("//button[@id='footer-btn']");

// Scroll to page extremes
page.scrollToTop();
page.scrollToBottom();
```

#### Scrolling Behavior

Scrolling simulates mouse wheel behavior:

1. **Tick-Based**: Scrolls in discrete "ticks" like a mouse wheel
2. **Variable Amount**: Each tick varies slightly in pixel amount
3. **Inter-Tick Delay**: Realistic timing between scroll events

Configure via `InteractionOptions` (see below).

---

### JavaScript Execution

| Method | Description | Returns |
|--------|-------------|---------|
| `evaluate(String script)` | Execute JS, return as string | `String` or `null` |
| `evaluateBoolean(String script)` | Execute JS, return boolean | `boolean` |
| `evaluateInt(String script)` | Execute JS, return integer | `int` |

```java
// Get computed value
String color = page.evaluate("getComputedStyle(document.body).backgroundColor");

// Check condition
boolean hasClass = page.evaluateBoolean(
    "document.querySelector('#element').classList.contains('active')"
);

// Get numeric value
int itemCount = page.evaluateInt(
    "document.querySelectorAll('.item').length"
);

// Execute complex script
String result = page.evaluate("""
    (function() {
        const data = [];
        document.querySelectorAll('.product').forEach(el => {
            data.push({
                name: el.querySelector('.name').textContent,
                price: el.querySelector('.price').textContent
            });
        });
        return JSON.stringify(data);
    })()
    """);

// Modify page state
page.evaluate("document.querySelector('#checkbox').checked = true");
page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
```

---

### Screenshots

| Method | Description | Returns |
|--------|-------------|---------|
| `screenshot()` | Capture full page | `byte[]` (PNG) |
| `screenshotElement(String xpath)` | Capture specific element | `byte[]` (PNG) |

```java
import java.nio.file.Files;
import java.nio.file.Path;

// Full page screenshot
byte[] fullPage = page.screenshot();
Files.write(Path.of("screenshot.png"), fullPage);

// Element screenshotBytes
byte[] element = page.screenshotElement("//div[@class='chart']");
Files.write(Path.of("chart.png"), element);
```

---

### Cookies

| Method | Description |
|--------|-------------|
| `getCookies()` | Get all cookies |
| `setCookie(name, value, domain)` | Set cookie (basic) |
| `setCookie(name, value, domain, path, secure, httpOnly)` | Set cookie (full) |
| `deleteCookies()` | Delete all cookies |
| `deleteCookie(name, domain)` | Delete specific cookie |

```java
import com.google.gson.JsonObject;

// Get all cookies
List<JsonObject> cookies = page.getCookies();
for (JsonObject cookie : cookies) {
    String name = cookie.get("name").getAsString();
    String value = cookie.get("value").getAsString();
    String domain = cookie.get("domain").getAsString();
    System.out.println(name + " = " + value + " (" + domain + ")");
}

// Set a cookie
page.setCookie("session_id", "abc123", ".example.com");

// Set cookie with full options
page.setCookie(
    "auth_token",
    "xyz789",
    ".example.com",
    "/",
    true,   // secure
    true    // httpOnly
);

// Delete specific cookie
page.deleteCookie("session_id", ".example.com");

// Delete all cookies
page.deleteCookies();
```

---

## InteractionOptions Configuration

`InteractionOptions` controls all timing and behavior for human-like interactions.

### Default Options

```java
InteractionOptions options = InteractionOptions.defaults();
```

### Fast Options (Less Human-Like)

```java
InteractionOptions options = InteractionOptions.fast();
```

### Custom Options

```java
InteractionOptions options = InteractionOptions.builder()

        // ===== Mouse Movement =====
        .simulateMousePath(true)        // Use Bezier curves (default: true)
        .moveSpeed(50)                  // 1-100, 0=random (default: 0)
        .overshootEnabled(true)         // Overshoot distant targets (default: true)
        .overshootThreshold(500.0)      // Min distance for overshoot (default: 500px)
        .overshootRadius(120.0)         // Max overshoot deviation (default: 120px)
        .jitterEnabled(true)            // Micro-tremor simulation (default: true)
        .jitterAmount(2.0)              // Jitter pixels (default: 2.0)

        // ===== Click Behavior =====
        .preClickDelayMin(50)           // Min hesitation before click (default: 50ms)
        .preClickDelayMax(200)          // Max hesitation before click (default: 200ms)
        .clickHoldDurationMin(50)       // Min mousedown duration (default: 50ms)
        .clickHoldDurationMax(150)      // Max mousedown duration (default: 150ms)
        .positionOffsetMax(5)           // Max offset from center (default: 5px)
        .paddingPercentage(20)          // Click area padding 0-100 (default: 20%)

        // ===== Typing Behavior =====
        .keystrokeDelayMin(50)          // Min inter-key delay (default: 50ms)
        .keystrokeDelayMax(180)         // Max inter-key delay (default: 180ms)
        .contextAwareTyping(true)       // Faster for common bigrams (default: true)
        .thinkingPauseProbability(0.02) // Chance of pause per keystroke (default: 2%)
        .thinkingPauseMin(300)          // Min thinking pause (default: 300ms)
        .thinkingPauseMax(800)          // Max thinking pause (default: 800ms)

        // ===== Scrolling =====
        .scrollTickPixels(110)          // Base pixels per tick (default: 110)
        .scrollTickVariance(20)         // Variance per tick (default: 20px)
        .scrollDelayMin(30)             // Min delay between ticks (default: 30ms)
        .scrollDelayMax(100)            // Max delay between ticks (default: 100ms)
        .scrollSpeed(50)                // Scroll speed 1-100 (default: 50)

        // ===== Waiting =====
        .defaultTimeout(30000)          // Default wait timeout (default: 30s)
        .retryInterval(500)             // Retry check interval (default: 500ms)
        .maxRetries(1)                  // Max retry attempts (default: 1)

        // ===== Move Delay =====
        .moveDelayMin(0)                // Min delay after move (default: 0ms)
        .moveDelayMax(100)              // Max delay after move (default: 100ms)
        .randomizeMoveDelay(true)       // Randomize vs always max (default: true)

        .build();
```

### Applying Custom Options

```java
// Option 1: Via BrowserManager (applies to all browsers)
BrowserManager manager = BrowserManager.builder()
        .executablePath("/path/to/chrome")
        .interactionOptions(customOptions)
        .build();

// Option 2: Create Page with custom options (advanced)
Page page = new Page(cdpClient, targetId, customOptions);
```

---

## Multi-Page Handling

```java
try (Browser browser = manager.createSession()) {
    // Get main page
    Page mainPage = browser.getPage();
    mainPage.navigate("https://example.com");

    // Open new tab
    Page newTab = browser.newPage("https://google.com");

    // Get all pages
    List<Page> allPages = browser.getPages();
    System.out.println("Open pages: " + browser.getPageCount());

    // Get page by target ID
    Page specific = browser.getPageByTargetId(newTab.getTargetId());

    // Close a tab
    browser.closePage(newTab);
}
```

---

## Error Handling

### TimeoutException

Most operations can throw `TimeoutException`:

```java
try {
    page.waitForSelector("//div[@id='result']", 5000);
} catch (TimeoutException e) {
    System.err.println("Element did not appear within 5 seconds");
    // Handle gracefully - maybe take screenshotBytes, log state, continue
}
```

### Null Checks

Element queries return `null` if not found (without waiting):

```java
BoundingBox box = page.querySelector("//div[@class='optional']");
if (box != null) {
    page.clickAtBox(box);
} else {
    System.out.println("Optional element not present, skipping");
}
```

### Comprehensive Error Handling

```java
public class RobustScript {
    private final Page page;
    private final List<String> warnings = new ArrayList<>();

    public RobustScript(Page page) {
        this.page = page;
    }

    public Result execute() {
        try {
            navigateWithRetry("https://example.com", 3);

            if (!clickIfExists("//button[@id='optional']")) {
                warnings.add("Optional button not found");
            }

            String data = extractDataSafely();

            return new Result(data, warnings);

        } catch (Exception e) {
            warnings.add("Fatal error: " + e.getMessage());
            return new Result(null, warnings);
        }
    }

    private void navigateWithRetry(String url, int maxAttempts) throws Exception {
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                page.navigate(url);
                return;
            } catch (TimeoutException e) {
                if (i == maxAttempts) throw e;
                warnings.add("Navigation attempt " + i + " failed, retrying...");
                Thread.sleep(2000);
            }
        }
    }

    private boolean clickIfExists(String xpath) {
        try {
            if (page.exists(xpath) && page.isVisible(xpath)) {
                page.click(xpath);
                return true;
            }
        } catch (Exception e) {
            warnings.add("Click failed for " + xpath + ": " + e.getMessage());
        }
        return false;
    }

    private String extractDataSafely() {
        try {
            return page.getText("//div[@class='data']");
        } catch (Exception e) {
            warnings.add("Data extraction failed: " + e.getMessage());
            return null;
        }
    }
}
```

---

## Script Examples

### Example 1: Login Script

```java
package org.nodriver4j.scripts;

import org.nodriver4j.core.Page;
import java.util.concurrent.TimeoutException;

public class LoginScript {

    private static final int TIMEOUT_MS = 10000;

    private final Page page;
    private final String username;
    private final String password;

    public LoginScript(Page page, String username, String password) {
        this.page = page;
        this.username = username;
        this.password = password;
    }

    public LoginResult execute() {
        try {
            // Navigate to login page
            page.navigate("https://example.com/login", TIMEOUT_MS);

            // Wait for form to load
            page.waitForSelector("//input[@name='username']", TIMEOUT_MS);

            // Fill credentials
            page.type("//input[@name='username']", username);
            page.type("//input[@name='password']", password);

            // Submit
            page.click("//button[@type='submit']");

            // Wait for redirect or error
            Thread.sleep(2000);

            // Check result
            if (page.exists("//div[@class='dashboard']")) {
                return new LoginResult(true, "Login successful");
            } else if (page.exists("//div[@class='error']")) {
                String error = page.getText("//div[@class='error']");
                return new LoginResult(false, error);
            } else {
                return new LoginResult(false, "Unknown state after login");
            }

        } catch (TimeoutException e) {
            return new LoginResult(false, "Timeout: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LoginResult(false, "Interrupted");
        }
    }

    public static class LoginResult {
        public final boolean success;
        public final String message;

        public LoginResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
```

### Example 2: Data Extraction Script

```java
package org.nodriver4j.scripts;

import org.nodriver4j.core.Page;
import org.nodriver4j.math.BoundingBox;
import java.util.*;
import java.util.concurrent.TimeoutException;

public class ProductScraper {

    private final Page page;
    private final String searchQuery;

    public ProductScraper(Page page, String searchQuery) {
        this.page = page;
        this.searchQuery = searchQuery;
    }

    public ScrapingResult execute() {
        List<Product> products = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            // Navigate and search
            page.navigate("https://example-store.com");
            page.type("//input[@name='search']", searchQuery);
            page.pressKey("Enter", false, false, false);

            // Wait for results
            page.waitForSelector("//div[@class='product-grid']");
            Thread.sleep(1000); // Let all products load

            // Extract product data
            List<BoundingBox> productCards = page.querySelectorAll("//div[@class='product-card']");

            for (int i = 0; i < productCards.size(); i++) {
                try {
                    String basePath = "(//div[@class='product-card'])[" + (i + 1) + "]";

                    String name = page.getText(basePath + "//h3[@class='name']");
                    String price = page.getText(basePath + "//span[@class='price']");
                    String link = page.getAttribute(basePath + "//a", "href");

                    products.add(new Product(name, price, link));

                } catch (Exception e) {
                    warnings.add("Failed to extract product " + (i + 1) + ": " + e.getMessage());
                }
            }

            // Handle pagination
            while (page.exists("//a[@class='next-page']") && products.size() < 100) {
                page.click("//a[@class='next-page']");
                page.waitForNetworkIdle(1000);
                // ... extract more products
            }

        } catch (TimeoutException e) {
            warnings.add("Timeout during scraping: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warnings.add("Scraping interrupted");
        }

        return new ScrapingResult(products, warnings);
    }

    public static class Product {
        public final String name;
        public final String price;
        public final String link;

        public Product(String name, String price, String link) {
            this.name = name;
            this.price = price;
            this.link = link;
        }
    }

    public static class ScrapingResult {
        public final List<Product> products;
        public final List<String> warnings;

        public ScrapingResult(List<Product> products, List<String> warnings) {
            this.products = Collections.unmodifiableList(products);
            this.warnings = Collections.unmodifiableList(warnings);
        }
    }
}
```

### Example 3: Form Automation Script

```java
package org.nodriver4j.scripts;

import org.nodriver4j.core.Page;
import java.util.Map;
import java.util.concurrent.TimeoutException;

public class FormFiller {

    private final Page page;
    private final String formUrl;
    private final Map<String, String> fieldData;

    public FormFiller(Page page, String formUrl, Map<String, String> fieldData) {
        this.page = page;
        this.formUrl = formUrl;
        this.fieldData = fieldData;
    }

    public FormResult execute() {
        try {
            page.navigate(formUrl);
            page.waitForSelector("//form");

            // Fill each field
            for (Map.Entry<String, String> entry : fieldData.entrySet()) {
                String fieldName = entry.getKey();
                String value = entry.getValue();

                String inputXPath = "//input[@name='" + fieldName + "']";
                String selectXPath = "//select[@name='" + fieldName + "']";
                String textareaXPath = "//textarea[@name='" + fieldName + "']";

                if (page.exists(inputXPath)) {
                    String inputType = page.getAttribute(inputXPath, "type");

                    if ("checkbox".equals(inputType) || "radio".equals(inputType)) {
                        if ("true".equals(value)) {
                            page.click(inputXPath);
                        }
                    } else {
                        page.clear(inputXPath);
                        page.type(inputXPath, value);
                    }
                } else if (page.exists(selectXPath)) {
                    page.select(selectXPath, value);
                } else if (page.exists(textareaXPath)) {
                    page.clear(textareaXPath);
                    page.type(textareaXPath, value);
                }

                // Small delay between fields (more human-like)
                Thread.sleep(200);
            }

            // Submit
            page.click("//button[@type='submit']");
            page.waitForNetworkIdle(2000);

            // Check for success
            if (page.exists("//div[@class='success']")) {
                return new FormResult(true, "Form submitted successfully");
            } else {
                String error = page.getText("//div[@class='error']");
                return new FormResult(false, error != null ? error : "Unknown error");
            }

        } catch (TimeoutException e) {
            return new FormResult(false, "Timeout: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FormResult(false, "Interrupted");
        }
    }

    public static class FormResult {
        public final boolean success;
        public final String message;

        public FormResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
```

---

## Best Practices Summary

1. **Always use try-with-resources** for Browser instances
2. **Inject Page via constructor** - scripts shouldn't create browsers
3. **Return result objects** with success status and warnings
4. **Handle TimeoutException** gracefully
5. **Check element existence** before interacting when appropriate
6. **Use realistic delays** between interactions
7. **Log important state changes** for debugging
8. **Keep scripts focused** on a single task
9. **Test with headless=false** first to see what's happening
10. **Capture screenshots** on failures for debugging

---

## See Also

- `ProfileWarmer.java` - Reference implementation for warming scripts
- `InteractionOptions.java` - Full configuration options
- `BrowserManager.java` - Browser lifecycle management
- `Page.java` - Core interaction API