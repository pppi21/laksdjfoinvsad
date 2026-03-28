package org.nodriver4j.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.nodriver4j.core.exceptions.ElementNotFoundException;
import org.nodriver4j.math.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alternative element-finding strategies beyond raw CSS/XPath.
 *
 * <p>Provides text-based, role-based, shadow-piercing, and chained
 * selectors modeled after Playwright's selector engine system.</p>
 *
 * <h3>Selector formats</h3>
 * <ul>
 *   <li>{@code text=Sign in} — find by visible text (exact match)</li>
 *   <li>{@code text/i=sign in} — find by visible text (case-insensitive substring)</li>
 *   <li>{@code role=button} — find by ARIA role</li>
 *   <li>{@code role=button[name="Submit"]} — find by ARIA role and accessible name</li>
 *   <li>{@code div.container >> text=Submit} — chained selector (each {@code >>}
 *       narrows the search scope)</li>
 * </ul>
 *
 * <p>This is an internal implementation class — scripts interact with
 * these operations through {@link Page}'s public API.</p>
 */
class SelectorEngine {

    // ==================== JS Helper Constants ====================

    // Implicit ARIA role mapping from HTML tag/type.
    private static final String IMPLICIT_ROLE_FN =
            "function getImplicitRole(el){" +
            "var t=el.tagName.toLowerCase(),tp=(el.getAttribute('type')||'').toLowerCase();" +
            "switch(t){" +
            "case 'a':return el.hasAttribute('href')?'link':null;" +
            "case 'button':return'button';" +
            "case 'input':switch(tp){" +
            "case 'checkbox':return'checkbox';case 'radio':return'radio';" +
            "case 'submit':case 'button':case 'reset':case 'image':return'button';" +
            "case 'range':return'slider';default:return'textbox';};" +
            "case 'textarea':return'textbox';" +
            "case 'select':return el.multiple?'listbox':'combobox';" +
            "case 'img':return'img';" +
            "case 'h1':case 'h2':case 'h3':case 'h4':case 'h5':case 'h6':return'heading';" +
            "case 'li':return'listitem';case 'ul':case 'ol':return'list';" +
            "case 'table':return'table';case 'tr':return'row';case 'td':return'cell';case 'th':return'columnheader';" +
            "case 'nav':return'navigation';case 'main':return'main';case 'form':return'form';" +
            "case 'dialog':return'dialog';case 'article':return'article';case 'aside':return'complementary';" +
            "case 'header':return'banner';case 'footer':return'contentinfo';" +
            "case 'option':return'option';case 'progress':return'progressbar';" +
            "default:return null;}}";

    // Resolve effective ARIA role (explicit > implicit).
    private static final String GET_ROLE_FN =
            "function getRole(el){" +
            "var r=el.getAttribute('role');" +
            "if(r)return r.trim().split(/\\s+/)[0];" +
            "return getImplicitRole(el);}";

    // Resolve accessible name (aria-labelledby > aria-label > label > text > title > placeholder).
    private static final String ACCESSIBLE_NAME_FN =
            "function getAccessibleName(el){" +
            "var lb=el.getAttribute('aria-labelledby');" +
            "if(lb){var ns=lb.split(/\\s+/).map(function(id){var r=document.getElementById(id);" +
            "return r?(r.textContent||'').trim():'';}).filter(Boolean);" +
            "if(ns.length)return ns.join(' ');}" +
            "var al=el.getAttribute('aria-label');" +
            "if(al&&al.trim())return al.trim();" +
            "if(el.labels&&el.labels.length)return Array.from(el.labels).map(function(l){return(l.textContent||'').trim();}).join(' ');" +
            "var role=getRole(el);" +
            "if(['button','link','heading','tab','menuitem','treeitem','option','cell','listitem'].indexOf(role)>=0)" +
            "return(el.textContent||'').replace(/\\s+/g,' ').trim();" +
            "if(el.title)return el.title.trim();" +
            "if(el.placeholder)return el.placeholder.trim();" +
            "return'';}";

    // All role-related helpers combined.
    private static final String ROLE_HELPERS = IMPLICIT_ROLE_FN + GET_ROLE_FN + ACCESSIBLE_NAME_FN;

    // Find all elements by text within a scope (returns array).
    private static final String TEXT_FIND_ALL_IN_SCOPE_FN =
            "function findAllTextInScope(root,text,exact){" +
            "var norm=function(s){return(s||'').replace(/\\s+/g,' ').trim();};" +
            "var st=exact?text:text.toLowerCase();" +
            "var all=root.querySelectorAll('*'),res=[];" +
            "for(var i=0;i<all.length;i++){var el=all[i],t=norm(el.textContent);" +
            "var m=exact?(t===st):(t.toLowerCase().indexOf(st)>=0);" +
            "if(!m)continue;" +
            "var cm=false;" +
            "for(var j=0;j<el.children.length;j++){var ct=norm(el.children[j].textContent);" +
            "if(exact?(ct===st):(ct.toLowerCase().indexOf(st)>=0)){cm=true;break;}}" +
            "if(!cm)res.push(el);}return res;}";

    // Find all elements by ARIA role within a scope (returns array).
    private static final String ROLE_FIND_ALL_IN_SCOPE_FN =
            "function findAllRoleInScope(root,role,name){" +
            "var all=root.querySelectorAll('*'),res=[];" +
            "for(var i=0;i<all.length;i++){var el=all[i];" +
            "if(getRole(el)!==role)continue;" +
            "if(name!==null){var n=getAccessibleName(el).replace(/\\s+/g,' ').trim();" +
            "if(n!==name)continue;}" +
            "res.push(el);}return res;}";

    private static final Pattern ROLE_NAME_PATTERN = Pattern.compile("name=[\"']([^\"']*)[\"']");

    private final Page page;

    SelectorEngine(Page page) {
        this.page = page;
    }

    // ==================== Detection ====================

    /**
     * Tests whether a selector uses engine syntax (text=, role=, >>).
     * Standard CSS and XPath selectors return false.
     */
    static boolean isEngineSelector(String selector) {
        if (selector == null) return false;
        return selector.startsWith("text=") ||
               selector.startsWith("text/i=") ||
               selector.startsWith("role=") ||
               selector.contains(" >> ");
    }

    // ==================== 2a: Text Selector ====================

    /**
     * Finds the first element whose visible text matches.
     *
     * <p>Walks the DOM and compares normalized {@code textContent}.
     * Prefers the deepest (most specific) matching element — if a parent
     * and its child both match, the child wins.</p>
     *
     * @param text  the text to search for
     * @param exact true for exact match, false for case-insensitive substring
     * @return the element's bounding box, or null
     */
    BoundingBox findByText(String text, boolean exact) {
        String script = buildTextFindScript(text, exact, false);
        String result = page.evaluate(script);
        return parseBoxOrNull(result);
    }

    List<BoundingBox> findAllByText(String text, boolean exact) {
        String script = buildTextFindScript(text, exact, true);
        return evaluateAndParseBoxList(script);
    }

    // ==================== 2b: Role Selector ====================

    /**
     * Finds the first element matching an ARIA role and optional accessible name.
     *
     * <p>Resolves both explicit ({@code role="button"}) and implicit roles
     * (e.g., {@code <button>} has implicit role "button"). Accessible name
     * resolution follows the WAI-ARIA spec: aria-labelledby, aria-label,
     * label association, text content, title, placeholder.</p>
     *
     * @param role the ARIA role to match (e.g., "button", "link", "textbox")
     * @param name the accessible name to match, or null for any name
     * @return the element's bounding box, or null
     */
    BoundingBox findByRole(String role, String name) {
        String script = buildRoleFindScript(role, name, false);
        String result = page.evaluate(script);
        return parseBoxOrNull(result);
    }

    List<BoundingBox> findAllByRole(String role, String name) {
        String script = buildRoleFindScript(role, name, true);
        return evaluateAndParseBoxList(script);
    }

    // ==================== 2c: Shadow-Piercing CSS ====================

    /**
     * Queries using CSS but recursively enters open shadow roots.
     *
     * <p>Modeled after Playwright's {@code _queryCSS} with shadow piercing:
     * queries the light DOM first, then for every shadow host, enters
     * its shadow root and recurses.</p>
     *
     * @param cssSelector standard CSS selector
     * @return the first matching element's bounding box, or null
     */
    BoundingBox querySelectorPiercing(String cssSelector) {
        String script = buildShadowPiercingScript(cssSelector, false);
        String result = page.evaluate(script);
        return parseBoxOrNull(result);
    }

    List<BoundingBox> querySelectorAllPiercing(String cssSelector) {
        String script = buildShadowPiercingScript(cssSelector, true);
        return evaluateAndParseBoxList(script);
    }

    // ==================== 2d: Chained Selectors ====================

    /**
     * Executes a Playwright-style {@code >>} chained selector.
     *
     * <p>Each segment narrows the search scope. Supports CSS, {@code text=},
     * and {@code role=} segments. Example:
     * {@code "div.container >> text=Submit >> role=button"}</p>
     *
     * @param chainedSelector the selector chain
     * @return the first matching element's bounding box, or null
     */
    BoundingBox find(String chainedSelector) {
        String script = buildChainedScript(chainedSelector, false);
        String result = page.evaluate(script);
        return parseBoxOrNull(result);
    }

    List<BoundingBox> findAll(String chainedSelector) {
        String script = buildChainedScript(chainedSelector, true);
        return evaluateAndParseBoxList(script);
    }

    // ==================== 2e: Unified Resolution ====================

    /**
     * Resolves any engine selector to a bounding box (instant, no polling).
     */
    BoundingBox resolve(String selector) {
        if (selector.contains(" >> ")) return find(selector);
        if (selector.startsWith("text="))   return findByText(selector.substring(5), true);
        if (selector.startsWith("text/i=")) return findByText(selector.substring(7), false);
        if (selector.startsWith("role=")) {
            RoleSpec rs = parseRoleSpec(selector.substring(5));
            return findByRole(rs.role, rs.name);
        }
        return null;
    }

    /**
     * Polls via rAF until the engine selector matches an element.
     */
    BoundingBox waitFor(String selector, int timeoutMs) {
        String script = buildFindScript(selector);
        String result = page.pollRaf(script, timeoutMs);
        if (result == null) {
            throw new ElementNotFoundException(selector);
        }
        return parseBoundingBox(result);
    }

    List<BoundingBox> resolveAll(String selector) {
        if (selector.contains(" >> ")) return findAll(selector);
        if (selector.startsWith("text="))   return findAllByText(selector.substring(5), true);
        if (selector.startsWith("text/i=")) return findAllByText(selector.substring(7), false);
        if (selector.startsWith("role=")) {
            RoleSpec rs = parseRoleSpec(selector.substring(5));
            return findAllByRole(rs.role, rs.name);
        }
        return List.of();
    }

    String resolveText(String selector) {
        String findJs = buildFindElementJs(selector);
        if (findJs == null) return null;
        return page.evaluate("(function(){var el=" + findJs + ";return el?el.innerText||el.textContent:null;})()");
    }

    String resolveAttribute(String selector, String attribute) {
        String findJs = buildFindElementJs(selector);
        if (findJs == null) return null;
        return page.evaluate("(function(){var el=" + findJs +
                ";return el?el.getAttribute('" + ElementQuery.escapeJs(attribute) + "'):null;})()");
    }

    String resolveValue(String selector) {
        String findJs = buildFindElementJs(selector);
        if (findJs == null) return null;
        return page.evaluate("(function(){var el=" + findJs + ";return el?el.value:null;})()");
    }

    boolean resolveExists(String selector) {
        return resolve(selector) != null;
    }

    boolean resolveVisible(String selector) {
        String findJs = buildFindElementJs(selector);
        if (findJs == null) return false;
        return page.evaluateBoolean("(function(){var el=" + findJs + ";if(!el)return false;" +
                "var r=el.getBoundingClientRect();" +
                "return r.width>0&&r.height>0&&window.getComputedStyle(el).visibility!=='hidden';})()");
    }

    // ==================== Find-Element JS Builders ====================

    /**
     * Builds a JS expression that evaluates to the target Element or null.
     * Used by {@link Actionability} for building state-check scripts.
     */
    String buildFindElementJs(String selector) {
        if (selector.contains(" >> ")) return buildChainedFindElementJs(selector);
        if (selector.startsWith("text="))   return buildTextFindElementJs(selector.substring(5), true);
        if (selector.startsWith("text/i=")) return buildTextFindElementJs(selector.substring(7), false);
        if (selector.startsWith("role=")) {
            RoleSpec rs = parseRoleSpec(selector.substring(5));
            return buildRoleFindElementJs(rs.role, rs.name);
        }
        return null;
    }

    /**
     * Builds a JS expression that evaluates to a JSON bounding-box string or null.
     * Suitable for use as a {@link Page#pollRaf} predicate.
     */
    private String buildFindScript(String selector) {
        if (selector.contains(" >> ")) return buildChainedScript(selector, false);
        if (selector.startsWith("text="))   return buildTextFindScript(selector.substring(5), true, false);
        if (selector.startsWith("text/i=")) return buildTextFindScript(selector.substring(7), false, false);
        if (selector.startsWith("role=")) {
            RoleSpec rs = parseRoleSpec(selector.substring(5));
            return buildRoleFindScript(rs.role, rs.name, false);
        }
        return "null";
    }

    // ---- Text ----

    private String buildTextFindElementJs(String text, boolean exact) {
        String escaped = ElementQuery.escapeJs(text);
        return "(function(){" +
                "var text='" + escaped + "',exact=" + exact + ";" +
                "var norm=function(s){return(s||'').replace(/\\s+/g,' ').trim();};" +
                "var st=exact?text:text.toLowerCase();" +
                "var all=document.querySelectorAll('*');" +
                "for(var i=0;i<all.length;i++){var el=all[i],t=norm(el.textContent);" +
                "var m=exact?(t===st):(t.toLowerCase().indexOf(st)>=0);" +
                "if(!m)continue;" +
                "var cm=false;" +
                "for(var j=0;j<el.children.length;j++){var ct=norm(el.children[j].textContent);" +
                "if(exact?(ct===st):(ct.toLowerCase().indexOf(st)>=0)){cm=true;break;}}" +
                "if(!cm){var r=el.getBoundingClientRect();" +
                "if(r.width>0&&r.height>0)return el;}}" +
                "return null;})()";
    }

    private String buildTextFindScript(String text, boolean exact, boolean multiple) {
        String escaped = ElementQuery.escapeJs(text);
        StringBuilder s = new StringBuilder();
        s.append("(function(){");
        s.append("var text='").append(escaped).append("',exact=").append(exact).append(";");
        s.append("var norm=function(s){return(s||'').replace(/\\s+/g,' ').trim();};");
        s.append("var st=exact?text:text.toLowerCase();");
        s.append("var all=document.querySelectorAll('*');");
        if (multiple) s.append("var results=[];");
        s.append("for(var i=0;i<all.length;i++){var el=all[i],t=norm(el.textContent);");
        s.append("var m=exact?(t===st):(t.toLowerCase().indexOf(st)>=0);");
        s.append("if(!m)continue;");
        s.append("var cm=false;");
        s.append("for(var j=0;j<el.children.length;j++){var ct=norm(el.children[j].textContent);");
        s.append("if(exact?(ct===st):(ct.toLowerCase().indexOf(st)>=0)){cm=true;break;}}");
        s.append("if(!cm){var r=el.getBoundingClientRect();");
        s.append("if(r.width>0&&r.height>0){");
        if (multiple) {
            s.append("results.push({x:r.x,y:r.y,width:r.width,height:r.height});}}}");
            s.append("return results.length?JSON.stringify(results):null;})()");
        } else {
            s.append("return JSON.stringify({x:r.x,y:r.y,width:r.width,height:r.height});}}}");
            s.append("return null;})()");
        }
        return s.toString();
    }

    // ---- Role ----

    private String buildRoleFindElementJs(String role, String name) {
        String escapedRole = ElementQuery.escapeJs(role);
        String nameJs = name != null ? "'" + ElementQuery.escapeJs(name) + "'" : "null";
        return "(function(){" +
                "var targetRole='" + escapedRole + "',targetName=" + nameJs + ";" +
                ROLE_HELPERS +
                "var all=document.querySelectorAll('*');" +
                "for(var i=0;i<all.length;i++){var el=all[i];" +
                "if(getRole(el)!==targetRole)continue;" +
                "if(targetName!==null){var n=getAccessibleName(el).replace(/\\s+/g,' ').trim();" +
                "if(n!==targetName)continue;}" +
                "var r=el.getBoundingClientRect();" +
                "if(r.width>0&&r.height>0)return el;}" +
                "return null;})()";
    }

    private String buildRoleFindScript(String role, String name, boolean multiple) {
        String escapedRole = ElementQuery.escapeJs(role);
        String nameJs = name != null ? "'" + ElementQuery.escapeJs(name) + "'" : "null";
        StringBuilder s = new StringBuilder();
        s.append("(function(){");
        s.append("var targetRole='").append(escapedRole).append("',targetName=").append(nameJs).append(";");
        s.append(ROLE_HELPERS);
        if (multiple) s.append("var results=[];");
        s.append("var all=document.querySelectorAll('*');");
        s.append("for(var i=0;i<all.length;i++){var el=all[i];");
        s.append("if(getRole(el)!==targetRole)continue;");
        s.append("if(targetName!==null){var n=getAccessibleName(el).replace(/\\s+/g,' ').trim();");
        s.append("if(n!==targetName)continue;}");
        s.append("var r=el.getBoundingClientRect();");
        s.append("if(r.width>0&&r.height>0){");
        if (multiple) {
            s.append("results.push({x:r.x,y:r.y,width:r.width,height:r.height});}}");
            s.append("return results.length?JSON.stringify(results):null;})()");
        } else {
            s.append("return JSON.stringify({x:r.x,y:r.y,width:r.width,height:r.height});}}");
            s.append("return null;})()");
        }
        return s.toString();
    }

    // ---- Shadow-piercing CSS ----

    private String buildShadowPiercingScript(String cssSelector, boolean multiple) {
        String escaped = ElementQuery.escapeCss(cssSelector).replace("'", "\\'");
        StringBuilder s = new StringBuilder();
        s.append("(function(){");
        s.append("var results=[];");
        s.append("function q(root){");
        s.append("try{results=results.concat([...root.querySelectorAll('").append(escaped).append("')]);}catch(e){}");
        s.append("if(root.shadowRoot)q(root.shadowRoot);");
        s.append("var els=root.querySelectorAll('*');");
        s.append("for(var i=0;i<els.length;i++){if(els[i].shadowRoot)q(els[i].shadowRoot);}}");
        s.append("q(document);");
        if (multiple) {
            s.append("var boxes=[];");
            s.append("for(var i=0;i<results.length;i++){var r=results[i].getBoundingClientRect();");
            s.append("if(r.width>0&&r.height>0)boxes.push({x:r.x,y:r.y,width:r.width,height:r.height});}");
            s.append("return boxes.length?JSON.stringify(boxes):null;})()");
        } else {
            s.append("for(var i=0;i<results.length;i++){var r=results[i].getBoundingClientRect();");
            s.append("if(r.width>0&&r.height>0)return JSON.stringify({x:r.x,y:r.y,width:r.width,height:r.height});}");
            s.append("return null;})()");
        }
        return s.toString();
    }

    // ---- Chained selectors ----

    private enum SegmentType { CSS, TEXT, ROLE }
    private record Segment(SegmentType type, String value, boolean exact, String roleName) {}

    private List<Segment> parseChain(String selector) {
        String[] parts = selector.split("\\s*>>\\s*");
        List<Segment> segments = new ArrayList<>();
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("text=")) {
                segments.add(new Segment(SegmentType.TEXT, part.substring(5), true, null));
            } else if (part.startsWith("text/i=")) {
                segments.add(new Segment(SegmentType.TEXT, part.substring(7), false, null));
            } else if (part.startsWith("role=")) {
                RoleSpec rs = parseRoleSpec(part.substring(5));
                segments.add(new Segment(SegmentType.ROLE, rs.role, false, rs.name));
            } else {
                segments.add(new Segment(SegmentType.CSS, part, false, null));
            }
        }
        return segments;
    }

    private String buildChainedScript(String selector, boolean multiple) {
        List<Segment> segments = parseChain(selector);

        StringBuilder js = new StringBuilder();
        js.append("(function(){");
        js.append("var scope=[document.documentElement];");
        js.append(TEXT_FIND_ALL_IN_SCOPE_FN);
        js.append(ROLE_HELPERS);
        js.append(ROLE_FIND_ALL_IN_SCOPE_FN);

        for (Segment seg : segments) {
            js.append("var ns=[];");
            js.append("for(var _i=0;_i<scope.length;_i++){var _r=scope[_i];");
            appendSegmentFind(js, seg);
            js.append("}");
            js.append("scope=ns;");
            js.append("if(scope.length===0)return null;");
        }

        if (multiple) {
            js.append("var boxes=[];");
            js.append("for(var i=0;i<scope.length;i++){var r=scope[i].getBoundingClientRect();");
            js.append("if(r.width>0&&r.height>0)boxes.push({x:r.x,y:r.y,width:r.width,height:r.height});}");
            js.append("return boxes.length?JSON.stringify(boxes):null;");
        } else {
            js.append("for(var i=0;i<scope.length;i++){var r=scope[i].getBoundingClientRect();");
            js.append("if(r.width>0&&r.height>0)return JSON.stringify({x:r.x,y:r.y,width:r.width,height:r.height});}");
            js.append("return null;");
        }
        js.append("})()");
        return js.toString();
    }

    private String buildChainedFindElementJs(String selector) {
        List<Segment> segments = parseChain(selector);

        StringBuilder js = new StringBuilder();
        js.append("(function(){");
        js.append("var scope=[document.documentElement];");
        js.append(TEXT_FIND_ALL_IN_SCOPE_FN);
        js.append(ROLE_HELPERS);
        js.append(ROLE_FIND_ALL_IN_SCOPE_FN);

        for (Segment seg : segments) {
            js.append("var ns=[];");
            js.append("for(var _i=0;_i<scope.length;_i++){var _r=scope[_i];");
            appendSegmentFind(js, seg);
            js.append("}");
            js.append("scope=ns;");
            js.append("if(scope.length===0)return null;");
        }

        js.append("return scope[0];})()");
        return js.toString();
    }

    /**
     * Appends the JS that finds elements for one chain segment and pushes them into {@code ns}.
     * Assumes the loop variable {@code _r} is the current scope root.
     */
    private void appendSegmentFind(StringBuilder js, Segment seg) {
        switch (seg.type) {
            case CSS -> {
                String escaped = ElementQuery.escapeCss(seg.value).replace("'", "\\'");
                js.append("ns=ns.concat([..._r.querySelectorAll('").append(escaped).append("')]);");
            }
            case TEXT -> {
                String escaped = ElementQuery.escapeJs(seg.value);
                js.append("ns=ns.concat(findAllTextInScope(_r,'")
                  .append(escaped).append("',").append(seg.exact).append("));");
            }
            case ROLE -> {
                String escapedRole = ElementQuery.escapeJs(seg.value);
                String nameJs = seg.roleName != null
                        ? "'" + ElementQuery.escapeJs(seg.roleName) + "'"
                        : "null";
                js.append("ns=ns.concat(findAllRoleInScope(_r,'")
                  .append(escapedRole).append("',").append(nameJs).append("));");
            }
        }
    }

    // ==================== Parsing Helpers ====================

    private record RoleSpec(String role, String name) {}

    private RoleSpec parseRoleSpec(String spec) {
        int bracket = spec.indexOf('[');
        if (bracket < 0) return new RoleSpec(spec.trim(), null);
        String role = spec.substring(0, bracket).trim();
        Matcher m = ROLE_NAME_PATTERN.matcher(spec.substring(bracket));
        return new RoleSpec(role, m.find() ? m.group(1) : null);
    }

    private BoundingBox parseBoxOrNull(String result) {
        if (result == null || "null".equals(result)) return null;
        return parseBoundingBox(result);
    }

    private BoundingBox parseBoundingBox(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        return new BoundingBox(
                obj.get("x").getAsDouble(),
                obj.get("y").getAsDouble(),
                obj.get("width").getAsDouble(),
                obj.get("height").getAsDouble());
    }

    private List<BoundingBox> evaluateAndParseBoxList(String script) {
        String result = page.evaluate(script);
        List<BoundingBox> boxes = new ArrayList<>();
        if (result == null || "null".equals(result) || "[]".equals(result)) return boxes;
        JsonArray array = JsonParser.parseString(result).getAsJsonArray();
        for (JsonElement el : array) {
            JsonObject obj = el.getAsJsonObject();
            boxes.add(new BoundingBox(
                    obj.get("x").getAsDouble(),
                    obj.get("y").getAsDouble(),
                    obj.get("width").getAsDouble(),
                    obj.get("height").getAsDouble()));
        }
        return boxes;
    }
}
