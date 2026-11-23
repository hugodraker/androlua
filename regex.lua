-- ============================================================================
-- Full Regular Expressions (Match, Extract, Replace & Split) in LuaJ
-- Backed by java.util.regex.Pattern (Android 1.6 - 15+ compatible)
-- ============================================================================

local Pattern      = luajava.bindClass("java.util.regex.Pattern")
local StringBuffer = luajava.bindClass("java.lang.StringBuffer")

-- 1. Test if pattern matches anywhere in string -> boolean
local function regex_test(text, regex)
    local m = Pattern:compile(regex):matcher(text)
    return m:find()
end

-- 2. Match first occurrence -> fullMatch, { group1, group2, ... } (or nil)
local function regex_match(text, regex)
    local m = Pattern:compile(regex):matcher(text)
    if not m:find() then
        return nil
    end
    local full = tostring(m:group(0))
    local groups = {}
    local count = m:groupCount()
    for i = 1, count do
        local g = m:group(i)
        groups[i] = (g ~= nil) and tostring(g) or ""
    end
    return full, groups
end

-- 3. Find all matches -> array of { match = "...", groups = { ... } }
local function regex_find_all(text, regex)
    local m = Pattern:compile(regex):matcher(text)
    local results = {}
    while m:find() do
        local entry = {
            match = tostring(m:group(0)),
            groups = {}
        }
        local count = m:groupCount()
        for i = 1, count do
            local g = m:group(i)
            entry.groups[i] = (g ~= nil) and tostring(g) or ""
        end
        results[#results + 1] = entry
    end
    return results
end

-- 4. Regex replace with $1, $2 capture group backreferences -> new string
local function regex_replace(text, regex, replacement)
    local m = Pattern:compile(regex):matcher(text)
    return tostring(m:replaceAll(replacement))
end

-- 5. Regex replace using a Lua callback function(fullMatch, groups) -> new string
local function regex_replace_fn(text, regex, fn)
    local m = Pattern:compile(regex):matcher(text)
    local sb = luajava.new(StringBuffer)
    while m:find() do
        local full = tostring(m:group(0))
        local groups = {}
        local count = m:groupCount()
        for i = 1, count do
            local g = m:group(i)
            groups[i] = (g ~= nil) and tostring(g) or ""
        end
        local repl = tostring(fn(full, groups) or full)
        -- Escape '\' and '$' in callback output so appendReplacement treats it literally
        repl = string.gsub(repl, "\\", "\\\\")
        repl = string.gsub(repl, "%$", "\\$")
        m:appendReplacement(sb, repl)
    end
    m:appendTail(sb)
    return tostring(sb:toString())
end

-- 6. Split string using regex delimiter -> 1-based Lua table
local function regex_split(text, regex)
    local arr = Pattern:compile(regex):split(text)
    local out = {}
    for i = 1, #arr do
        out[i] = tostring(arr[i])
    end
    return out
end

-- ============================================================================
-- Demonstration Cases
-- ============================================================================
local output = {}
local function out(line)
    output[#output + 1] = line
    log(line)
end

out("=== 1. FULL REGEX MATCHING (ALTERNATION & QUANTIFIERS) ===")
-- Matches Canadian postal codes (A1A 1A1) OR US ZIP+4 codes (12345 or 12345-6789)
local postalRegex = "(?i)\\b([A-Z]\\d[A-Z][ -]?\\d[A-Z]\\d|\\d{5}(?:-\\d{4})?)\\b"
local addressText = "Ship from Mississauga ON L5B 2C9 to New York NY 10001-1234 or Toronto M5V3L9."

local allCodes = regex_find_all(addressText, postalRegex)
out("Found " .. #allCodes .. " postal/ZIP codes:")
for i = 1, #allCodes do
    out("  [" .. i .. "] " .. allCodes[i].match)
end

out("\n=== 2. CAPTURE GROUP EXTRACTION ===")
local logLine = "2026-11-21 22:12:05 [SYNC] host=192.168.1.50 share=PUBLIC files=42 status=OK"
local full, caps = regex_match(logLine, "^(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2})\\s+\\[([A-Z]+)\\].*files=(\\d+)")
if full then
    out(string.format("Date=%s | Time=%s | Tag=%s | Files=%s", caps[1], caps[2], caps[3], caps[4]))
end

out("\n=== 3. REGEX REPLACE WITH BACKREFERENCES ($1, $2) ===")
-- Reformat ISO dates YYYY-MM-DD -> DD/MM/YYYY
local reformatted = regex_replace("Backup dates: 2026-11-21 and 2026-12-01", "(\\d{4})-(\\d{2})-(\\d{2})", "$3/$2/$1")
out("Reformatted dates: " .. reformatted)

-- Redact passwords/tokens in INI or URL strings
local iniSample = "user=admin&pass=SuperSecret123!&host=10.0.0.5"
local redacted = regex_replace(iniSample, "(?i)(pass|password|token)=([^&\\s]+)", "$1=***REDACTED***")
out("Redacted string:   " .. redacted)

-- Strip HTML tags
local cleanText = regex_replace("<h3>Title</h3><p>Hello <b>World</b> &amp; Lua!</p>", "<[^>]+>", "")
out("Stripped HTML:     " .. cleanText)

out("\n=== 4. REGEX REPLACE WITH LUA CALLBACK FUNCTION ===")
-- Convert all temperatures in Fahrenheit (e.g., 68F, 212F) to Celsius
local weather = "Server room: 68F, CPU: 149F, Ambient: 77F"
local converted = regex_replace_fn(weather, "(\\d+(?:\\.\\d+)?)F\\b", function(fullMatch, g)
    local f = tonumber(g[1])
    local c = (f - 32) * 5 / 9
    return string.format("%.1fC", c)
end)
out("Callback replace:  " .. converted)

out("\n=== 5. REGEX SPLIT ===")
local parts = regex_split("apple, banana;  cherry | date\tfig", "\\s*[,;|\\t]\\s*")
out("Split count=" .. #parts .. " -> " .. table.concat(parts, " / "))

out("\n=== 6. BUILT-IN LUA PATTERNS (LIGHTWEIGHT) ===")
local luaSub = string.gsub("Order #1045 confirmed", "#(%d+)", "#[%1]")
out("Lua string.gsub:   " .. luaSub)

-- Display results onscreen if foreground Activity is open
local summary = table.concat(output, "\n")
if ACTIVITY ~= nil then
    dialog("Lua Regex & Replace Results", summary)
else
    toast("Regex demo complete (" .. #allCodes .. " matches)", true)
end

return "Regex demo OK"