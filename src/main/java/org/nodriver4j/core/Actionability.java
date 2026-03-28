package org.nodriver4j.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.nodriver4j.core.exceptions.ElementNotInteractableException;
import org.nodriver4j.math.BoundingBox;

import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * Element state checks and pre-action validation.
 *
 * <p>Provides actionability verification before performing interactions.
 * {@link InputController} calls into this class before clicking, typing,
 * hovering, etc. to ensure the target element is in a valid state.</p>
 *
 * <h3>Supported states (1a)</h3>
 * <ul>
 *   <li>{@code visible} — non-empty bounding box AND not {@code visibility:hidden}</li>
 *   <li>{@code hidden} — inverse of visible</li>
 *   <li>{@code enabled} — not disabled (attribute, fieldset, or aria-disabled)</li>
 *   <li>{@code disabled} — inverse of enabled</li>
 *   <li>{@code editable} — enabled AND not readonly</li>
 *   <li>{@code stable} — bounding box unchanged across 2 consecutive rAF frames</li>
 *   <li>{@code checked} — checked property is true</li>
 *   <li>{@code unchecked} — checked property is false</li>
 * </ul>
 *
 * <h3>Smart retargeting (1d)</h3>
 * <p>For enabled/disabled/editable checks, the element is retargeted to the
 * nearest interactive ancestor (button, link, input, select, textarea).
 * This means clicking text inside a button checks the <em>button's</em> state.</p>
 *
 * <p>This is an internal implementation class — scripts interact with
 * these operations through {@link Page}'s public API.</p>
 */
class Actionability {

    // JS helper: retargets to nearest interactive ancestor for enabled/disabled checks.
    // Modeled after Playwright's injectedScript.retarget() with 'follow-label' behavior.
    private static final String RETARGET_FN =
            "function retarget(el){" +
            "if(el.matches&&el.matches('input,textarea,select,button,fieldset,optgroup,option'))return el;" +
            "var label=el.closest('label');" +
            "if(label&&label.control)return label.control;" +
            "return el.closest('button,[role=button],a,[role=link],input,textarea,select')||el;}";

    // JS helper: checks if an element is disabled (attribute, fieldset, aria-disabled).
    private static final String IS_DISABLED_FN =
            "function isDisabled(el){" +
            "if(el.disabled!==undefined&&el.disabled)return true;" +
            "if(el.matches&&el.matches('button,select,input,textarea,option,optgroup')&&el.hasAttribute('disabled'))return true;" +
            "var fs=el.closest('fieldset[disabled]');" +
            "if(fs){var lg=el.closest('legend');if(!lg||lg.parentElement!==fs)return true;}" +
            "var n=el;while(n){if(n.getAttribute&&n.getAttribute('aria-disabled')==='true')return true;n=n.parentElement;}" +
            "return false;}";

    private final Page page;

    Actionability(Page page) {
        this.page = page;
    }

    // ==================== Element State Checks (1a) ====================

    /**
     * Checks if an element currently satisfies the given state.
     *
     * <p>For all states except {@code stable}, this is an instant check.
     * The {@code stable} state requires multi-frame observation via
     * {@code requestAnimationFrame} and uses a short internal timeout.</p>
     *
     * @param selector CSS or XPath selector
     * @param state    one of: visible, hidden, enabled, disabled, editable, stable, checked, unchecked
     * @return true if the element satisfies the state
     */
    boolean checkState(String selector, String state) {
        if ("stable".equals(state)) {
            return checkStable(selector, 5000);
        }
        String findEl = buildFindElement(selector);
        String script = buildStateCheckScript(findEl, state);
        return page.evaluateBoolean(script);
    }

    // ==================== Pre-Action Validation (1b) ====================

    /**
     * Waits until an element satisfies ALL required states simultaneously.
     *
     * <p>Uses a single CDP call with a browser-side Promise that polls via
     * {@code requestAnimationFrame}. Stability tracking (if requested) uses
     * closure variables to compare bounding rects across consecutive frames.</p>
     *
     * @param selector       CSS or XPath selector
     * @param requiredStates states that must all be true at the same time
     * @param timeoutMs      maximum time to wait
     * @return the element's bounding box once all states are satisfied
     * @throws ElementNotInteractableException if timeout expires
     */
    BoundingBox waitForActionable(String selector, String[] requiredStates, int timeoutMs) {
        String findEl = buildFindElement(selector);
        Set<String> states = Set.of(requiredStates);

        boolean needVisible = states.contains("visible");
        boolean needEnabled = states.contains("enabled");
        boolean needEditable = states.contains("editable");
        boolean needStable = states.contains("stable");

        StringBuilder js = new StringBuilder();
        js.append("new Promise((resolve)=>{");
        js.append("var deadline=Date.now()+").append(timeoutMs).append(";");
        js.append("var lastRectStr=null,stableFrames=0;");
        js.append("function findEl(){return ").append(findEl).append(";}");

        if (needEnabled || needEditable) {
            js.append(RETARGET_FN);
            js.append(IS_DISABLED_FN);
        }

        js.append("function poll(){");
        js.append("if(Date.now()>=deadline){resolve(null);return;}");
        js.append("var el=findEl();");
        js.append("if(!el){lastRectStr=null;stableFrames=0;requestAnimationFrame(poll);return;}");
        js.append("var rect=el.getBoundingClientRect();");

        if (needVisible) {
            js.append("var style=window.getComputedStyle(el);");
            js.append("if(rect.width<=0||rect.height<=0||style.visibility==='hidden'||style.opacity==='0'){");
            js.append("lastRectStr=null;stableFrames=0;requestAnimationFrame(poll);return;}");
        }

        if (needEnabled) {
            js.append("if(isDisabled(retarget(el))){");
            js.append("lastRectStr=null;stableFrames=0;requestAnimationFrame(poll);return;}");
        }

        if (needEditable) {
            js.append("var te=retarget(el);");
            js.append("if(isDisabled(te)||");
            js.append("(te.matches&&te.matches('input,textarea,select')&&te.hasAttribute('readonly'))){");
            js.append("lastRectStr=null;stableFrames=0;requestAnimationFrame(poll);return;}");
        }

        if (needStable) {
            js.append("var curStr=rect.x+'|'+rect.y+'|'+rect.width+'|'+rect.height;");
            js.append("if(lastRectStr===curStr){stableFrames++;}else{stableFrames=0;}");
            js.append("lastRectStr=curStr;");
            js.append("if(stableFrames<2){requestAnimationFrame(poll);return;}");
        }

        js.append("resolve(JSON.stringify({x:rect.x,y:rect.y,width:rect.width,height:rect.height}));");
        js.append("}");
        js.append("requestAnimationFrame(poll);");
        js.append("})");

        try {
            JsonObject params = new JsonObject();
            params.addProperty("expression", js.toString());
            params.addProperty("returnByValue", true);
            params.addProperty("awaitPromise", true);

            JsonObject result = page.cdpSession().send("Runtime.evaluate", params,
                    timeoutMs + 5000, TimeUnit.MILLISECONDS);

            if (result.has("result")) {
                JsonObject resultObj = result.getAsJsonObject("result");
                if (resultObj.has("value")) {
                    JsonElement value = resultObj.get("value");
                    if (!value.isJsonNull() && value.isJsonPrimitive()) {
                        String json = value.getAsString();
                        JsonObject box = JsonParser.parseString(json).getAsJsonObject();
                        return new BoundingBox(
                                box.get("x").getAsDouble(),
                                box.get("y").getAsDouble(),
                                box.get("width").getAsDouble(),
                                box.get("height").getAsDouble());
                    }
                }
            }

            throw new ElementNotInteractableException(
                    "Element not actionable within " + timeoutMs + "ms (required: " +
                    String.join(", ", requiredStates) + ")", selector);

        } catch (ElementNotInteractableException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new ElementNotInteractableException(
                    "Actionability wait timed out after " + timeoutMs + "ms", selector, e);
        }
    }

    // ==================== Hit Target Verification (1c) ====================

    /**
     * Verifies the element at the given selector would actually receive a click
     * at the specified coordinates.
     *
     * <p>Uses {@code document.elementFromPoint(x, y)} and checks whether the
     * hit element is the target or a descendant of it. Walks up through shadow
     * DOM boundaries (via {@code assignedSlot} and shadow host) for thorough
     * checking.</p>
     *
     * @param selector CSS or XPath selector for the intended click target
     * @param x        viewport x coordinate of the intended click
     * @param y        viewport y coordinate of the intended click
     * @throws ElementNotInteractableException if another element would intercept the click
     */
    void verifyHitTarget(String selector, double x, double y) {
        String findEl = buildFindElement(selector);

        String script =
                "(function(){" +
                "var target=" + findEl + ";" +
                "if(!target)return JSON.stringify({error:'not_found'});" +
                "var hit=document.elementFromPoint(" + x + "," + y + ");" +
                "if(!hit)return JSON.stringify({error:'no_hit'});" +
                // Direct containment check
                "if(hit===target||target.contains(hit))return JSON.stringify({ok:true});" +
                // Walk up through shadow DOM (composed tree)
                "var node=hit;" +
                "while(node){" +
                "if(node===target)return JSON.stringify({ok:true});" +
                "node=node.assignedSlot||node.parentElement||" +
                "(node.getRootNode&&node.getRootNode()!==document?node.getRootNode().host:null);" +
                "}" +
                // Describe the blocking element
                "var desc=hit.tagName.toLowerCase();" +
                "if(hit.id)desc+='#'+hit.id;" +
                "if(hit.className&&typeof hit.className==='string'){" +
                "var cls=hit.className.trim();if(cls)desc+='.'+cls.split(/\\s+/).join('.');}" +
                "return JSON.stringify({error:'blocked',desc:desc});" +
                "})()";

        String result = page.evaluate(script);
        if (result == null) return;

        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        if (json.has("ok")) return;

        String error = json.has("error") ? json.get("error").getAsString() : "unknown";
        if ("blocked".equals(error)) {
            String desc = json.has("desc") ? json.get("desc").getAsString() : "unknown element";
            throw new ElementNotInteractableException(
                    "Element is obscured by <" + desc + "> at (" + (int) x + ", " + (int) y + ")",
                    selector);
        }
    }

    // ==================== Internal Helpers ====================

    /**
     * Checks element stability by comparing bounding rects across rAF frames.
     */
    private boolean checkStable(String selector, int timeoutMs) {
        String findEl = buildFindElement(selector);
        String predicate =
                "(function(){" +
                "var el=" + findEl + ";" +
                "if(!el)return null;" +
                "var r=el.getBoundingClientRect();" +
                "var cur=r.x+'|'+r.y+'|'+r.width+'|'+r.height;" +
                "if(window.__nd4j_stableCheck===cur){delete window.__nd4j_stableCheck;return true;}" +
                "window.__nd4j_stableCheck=cur;" +
                "return null;" +
                "})()";
        return page.pollRaf(predicate, timeoutMs) != null;
    }

    /**
     * Builds a JS expression that finds an element by selector.
     * Supports CSS, XPath, and engine selectors (text=, role=, >>).
     */
    private String buildFindElement(String selector) {
        if (SelectorEngine.isEngineSelector(selector)) {
            return page.selectorEngine().buildFindElementJs(selector);
        }
        if (ElementQuery.isXPath(selector)) {
            return "document.evaluate(\"" + ElementQuery.escapeXPath(selector) +
                    "\",document,null,XPathResult.FIRST_ORDERED_NODE_TYPE,null).singleNodeValue";
        }
        return "document.querySelector(\"" + ElementQuery.escapeCss(selector) + "\")";
    }

    /**
     * Builds a JS script that checks a single element state (except stable).
     */
    private String buildStateCheckScript(String findEl, String state) {
        return switch (state) {
            case "visible" ->
                    "(function(){var el=" + findEl + ";if(!el)return false;" +
                    "var r=el.getBoundingClientRect();var s=window.getComputedStyle(el);" +
                    "return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.opacity!=='0';})()";

            case "hidden" ->
                    "(function(){var el=" + findEl + ";if(!el)return true;" +
                    "var r=el.getBoundingClientRect();var s=window.getComputedStyle(el);" +
                    "return r.width<=0||r.height<=0||s.visibility==='hidden'||s.opacity==='0';})()";

            case "enabled" ->
                    "(function(){var el=" + findEl + ";if(!el)return false;" +
                    RETARGET_FN + IS_DISABLED_FN +
                    "return!isDisabled(retarget(el));})()";

            case "disabled" ->
                    "(function(){var el=" + findEl + ";if(!el)return false;" +
                    RETARGET_FN + IS_DISABLED_FN +
                    "return isDisabled(retarget(el));})()";

            case "editable" ->
                    "(function(){var el=" + findEl + ";if(!el)return false;" +
                    RETARGET_FN + IS_DISABLED_FN +
                    "var t=retarget(el);" +
                    "if(isDisabled(t))return false;" +
                    "if(t.matches&&t.matches('input,textarea,select')&&t.hasAttribute('readonly'))return false;" +
                    "return true;})()";

            case "checked" ->
                    "(function(){var el=" + findEl + ";return el?!!el.checked:false;})()";

            case "unchecked" ->
                    "(function(){var el=" + findEl + ";return el?el.checked===false:false;})()";

            default -> throw new IllegalArgumentException("Unknown element state: " + state);
        };
    }
}
