-- ============================================================================
-- Asyncer Interactive Android Form in LuaJ
-- Demonstrates: Dropdowns (Spinner), Text Inputs (EditText), Labels (TextView),
--               Checkbox, and Action Buttons wired to SQLDroid, Sync, TTS & Files
-- ============================================================================

if ACTIVITY == nil then
    log("Error: Open Asyncer onscreen before running interactive_form.lua")
    toast("Open Asyncer UI to show the Lua form", true)
    return "No foreground Activity"
end

-- Bind Android & Java classes via luajava
local AlertDialogBuilder = luajava.bindClass("android.app.AlertDialog$Builder")
local ScrollView         = luajava.bindClass("android.widget.ScrollView")
local LinearLayout       = luajava.bindClass("android.widget.LinearLayout")
local LayoutParams       = luajava.bindClass("android.widget.LinearLayout$LayoutParams")
local TextView           = luajava.bindClass("android.widget.TextView")
local EditText           = luajava.bindClass("android.widget.EditText")
local Spinner            = luajava.bindClass("android.widget.Spinner")
local ArrayAdapter       = luajava.bindClass("android.widget.ArrayAdapter")
local CheckBox           = luajava.bindClass("android.widget.CheckBox")
local Button             = luajava.bindClass("android.widget.Button")
local R_layout           = luajava.bindClass("android.R$layout")
local ArrayList          = luajava.bindClass("java.util.ArrayList")
local LinkedBlockingQueue= luajava.bindClass("java.util.concurrent.LinkedBlockingQueue")

local MATCH_PARENT = -1
local WRAP_CONTENT = -2
local VERTICAL     = 1
local HORIZONTAL   = 0

-- Event queue bridges Android UI button clicks back to this Lua thread safely
local eventQueue = luajava.new(LinkedBlockingQueue)

-- Helper to populate a native Android Spinner dropdown
local function createDropdown(items)
    local list = luajava.new(ArrayList)
    for i = 1, #items do
        list:add(items[i])
    end
    local adapter = luajava.new(ArrayAdapter, ACTIVITY, R_layout.simple_spinner_item, list)
    adapter:setDropDownViewResource(R_layout.simple_spinner_dropdown_item)
    local spinner = luajava.new(Spinner, ACTIVITY)
    spinner:setAdapter(adapter)
    return spinner
end

-- Widget references shared between UI setup and Lua event loop
local ui = {}

ACTIVITY:runOnUiThread(luajava.createProxy("java.lang.Runnable", {
    run = function()
        local scroll = luajava.new(ScrollView, ACTIVITY)
        local root = luajava.new(LinearLayout, ACTIVITY)
        root:setOrientation(VERTICAL)
        root:setPadding(28, 20, 28, 20)

        -- 1. Category Dropdown
        local lblCat = luajava.new(TextView, ACTIVITY)
        lblCat:setText("Category / Action Type:")
        root:addView(lblCat)

        ui.spCategory = createDropdown({
            "Field Note",
            "Equipment Check",
            "Sync Trigger",
            "Urgent Alert"
        })
        root:addView(ui.spCategory, luajava.new(LayoutParams, MATCH_PARENT, WRAP_CONTENT))

        -- 2. Sync Job Dropdown
        local lblJob = luajava.new(TextView, ACTIVITY)
        lblJob:setText("Target Sync Profile:")
        root:addView(lblJob)

        ui.spJob = createDropdown({
            "Job 1",
            "Job 2",
            "Job 3",
            "Job 4",
            "Job 5"
        })
        root:addView(ui.spJob, luajava.new(LayoutParams, MATCH_PARENT, WRAP_CONTENT))

        -- 3. Text Input: Title
        local lblTitle = luajava.new(TextView, ACTIVITY)
        lblTitle:setText("Title / Tag:")
        root:addView(lblTitle)

        ui.etTitle = luajava.new(EditText, ACTIVITY)
        ui.etTitle:setSingleLine(true)
        ui.etTitle:setText("Inspection #1")
        root:addView(ui.etTitle, luajava.new(LayoutParams, MATCH_PARENT, WRAP_CONTENT))

        -- 4. Text Input: Multi-line Notes
        local lblNotes = luajava.new(TextView, ACTIVITY)
        lblNotes:setText("Notes / Details:")
        root:addView(lblNotes)

        ui.etNotes = luajava.new(EditText, ACTIVITY)
        ui.etNotes:setMinLines(2)
        ui.etNotes:setHint("Enter details (quotes, symbols & Unicode supported)...")
        root:addView(ui.etNotes, luajava.new(LayoutParams, MATCH_PARENT, WRAP_CONTENT))

        -- 5. Checkbox Option
        ui.cbSpeak = luajava.new(CheckBox, ACTIVITY)
        ui.cbSpeak:setText("Speak confirmation aloud (TTS)")
        ui.cbSpeak:setChecked(false)
        root:addView(ui.cbSpeak)

        -- Live selection listener on Category Dropdown
        ui.spCategory:setOnItemSelectedListener(luajava.createProxy("android.widget.AdapterView$OnItemSelectedListener", {
            onItemSelected = function(parent, view, pos, id)
                local chosen = tostring(ui.spCategory:getSelectedItem())
                ui.tvStatus:setText("Selected category: " .. chosen)
            end,
            onNothingSelected = function(parent) end
        }))

        -- Helper to wire a Button to push an action command to the Lua queue
        local function addActionButton(parentRow, label, actionName)
            local btn = luajava.new(Button, ACTIVITY)
            btn:setText(label)
            btn:setOnClickListener(luajava.createProxy("android.view.View$OnClickListener", {
                onClick = function(v)
                    eventQueue:offer(actionName)
                end
            }))
            parentRow:addView(btn, luajava.new(LayoutParams, 0, WRAP_CONTENT, 1.0))
            return btn
        end

        -- 6. Button Row 1: Save to SQLite & Query Recent
        local row1 = luajava.new(LinearLayout, ACTIVITY)
        row1:setOrientation(HORIZONTAL)
        addActionButton(row1, "Save to SQL", "SAVE_SQL")
        addActionButton(row1, "View Records", "LOAD_SQL")
        root:addView(row1, luajava.new(LayoutParams, MATCH_PARENT, WRAP_CONTENT))

        -- 7. Button Row 2: Run Sync Job & Copy Summary
        local row2 = luajava.new(LinearLayout, ACTIVITY)
        row2:setOrientation(HORIZONTAL)
        addActionButton(row2, "Run Sync Job", "RUN_SYNC")
        addActionButton(row2, "Copy & Toast", "COPY_SUMMARY")
        root:addView(row2, luajava.new(LayoutParams, MATCH_PARENT, WRAP_CONTENT))

        -- 8. Live Status / Output Box inside the Form
        ui.tvStatus = luajava.new(TextView, ACTIVITY)
        ui.tvStatus:setPadding(8, 14, 8, 14)
        ui.tvStatus:setText("Form ready. Choose options and tap a button.")
        root:addView(ui.tvStatus, luajava.new(LayoutParams, MATCH_PARENT, WRAP_CONTENT))

        -- 9. Close Button
        local btnClose = luajava.new(Button, ACTIVITY)
        btnClose:setText("Close Form")
        btnClose:setOnClickListener(luajava.createProxy("android.view.View$OnClickListener", {
            onClick = function(v)
                ui.dialog:dismiss()
            end
        }))
        root:addView(btnClose, luajava.new(LayoutParams, MATCH_PARENT, WRAP_CONTENT))

        scroll:addView(root)

        local builder = luajava.new(AlertDialogBuilder, ACTIVITY)
        builder:setTitle("Asyncer Lua Control Form")
        builder:setView(scroll)
        builder:setCancelable(true)

        ui.dialog = builder:create()
        ui.dialog:setOnDismissListener(luajava.createProxy("android.content.DialogInterface$OnDismissListener", {
            onDismiss = function(d)
                eventQueue:offer("CLOSED")
            end
        }))
        ui.dialog:show()
    end
}))

-- Helper to update the form's status label safely from the Lua thread
local function setFormStatus(msg)
    log(msg)
    ACTIVITY:runOnUiThread(luajava.createProxy("java.lang.Runnable", {
        run = function()
            if ui.tvStatus ~= nil then
                ui.tvStatus:setText(msg)
            end
        end
    }))
end

-- Helper to read current form values safely from UI widgets
local function readFormValues()
    local data = {}
    local latch = luajava.new(java.util.concurrent.CountDownLatch, 1)
    ACTIVITY:runOnUiThread(luajava.createProxy("java.lang.Runnable", {
        run = function()
            data.category  = tostring(ui.spCategory:getSelectedItem())
            data.jobIndex  = ui.spJob:getSelectedItemPosition() + 1
            data.jobLabel  = tostring(ui.spJob:getSelectedItem())
            data.title     = tostring(ui.etTitle:getText():toString())
            data.notes     = tostring(ui.etNotes:getText():toString())
            data.speak     = ui.cbSpeak:isChecked()
            latch:countDown()
        end
    }))
    latch:await()
    return data
end

-- ============================================================================
-- Main Lua Event Loop (handles button clicks until the form is closed)
-- ============================================================================
local dbPath   = sdcard_dir() .. "/sync/form_demo.db"
local noteFile = sdcard_dir() .. "/sync/form_notes.txt"

while true do
    local cmd = tostring(eventQueue:take())
    if cmd == "CLOSED" then
        break
    end

    local f = readFormValues()

    if cmd == "SAVE_SQL" then
        local escCat   = string.gsub(f.category, "'", "''")
        local escTitle = string.gsub(f.title, "'", "''")
        local escNotes = string.gsub(f.notes, "'", "''")

        local sql = [[
            CREATE TABLE IF NOT EXISTS form_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT,
                job_name TEXT,
                title TEXT,
                notes TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            );
            INSERT INTO form_entries (category, job_name, title, notes)
            VALUES (']] .. escCat .. [[', ']] .. f.jobLabel .. [[', ']] .. escTitle .. [[', ']] .. escNotes .. [[');
        ]]

        local res, err = sql_query(dbPath, sql)
        if err then
            setFormStatus("SQL Error: " .. err)
        else
            write_file(noteFile, string.format("[%s] %s (%s): %s\n", f.category, f.title, f.jobLabel, f.notes), true)
            local msg = "Saved '" .. f.title .. "' [" .. f.category .. "] to SQLite & text log."
            setFormStatus(msg)
            if f.speak then speak(msg, 1.0, 1.0) end
        end

    elseif cmd == "LOAD_SQL" then
        local rows, err = sql_query(dbPath, "SELECT id, category, job_name, title, notes FROM form_entries ORDER BY id DESC LIMIT 5;")
        if err then
            setFormStatus("Query Error: " .. err)
        elseif #rows == 0 then
            setFormStatus("No records saved yet. Tap 'Save to SQL' first.")
        else
            local lines = { "Latest " .. #rows .. " SQLite records:" }
            for i = 1, #rows do
                local r = rows[i]
                lines[#lines + 1] = string.format("#%s [%s | %s] %s: %s",
                    tostring(r.id), tostring(r.category), tostring(r.job_name), tostring(r.title), tostring(r.notes))
            end
            setFormStatus(table.concat(lines, "\n"))
        end

    elseif cmd == "RUN_SYNC" then
        setFormStatus("Running " .. f.jobLabel .. "...")
        local syncOut = run_sync(f.jobIndex)
        setFormStatus(f.jobLabel .. " result:\n" .. tostring(syncOut))
        if f.speak then speak(f.jobLabel .. " finished", 1.0, 1.0) end

    elseif cmd == "COPY_SUMMARY" then
        local summary = string.format("[%s] %s (%s) - %s", f.category, f.title, f.jobLabel, f.notes)
        set_clipboard(summary)
        toast("Copied to clipboard!", false)
        setFormStatus("Copied: " .. summary)
        if f.speak then speak("Copied to clipboard", 1.0, 1.0) end
    end
end

log("Interactive Lua form closed.")
return "Form session ended"