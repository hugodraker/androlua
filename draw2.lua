-- ============================================================================
-- Alternative LuaJ Drawing Script (Lines, Boxes, Circles, Text & Touch Draw)
-- Uses Enum:valueOf + BitmapDrawable + explicit pixel sizing + pcall logging
-- ============================================================================

if ACTIVITY == nil then
    toast("Open Asyncer onscreen before running this script", true)
    return "No foreground Activity"
end

local AlertDialogBuilder  = luajava.bindClass("android.app.AlertDialog$Builder")
local ScrollView          = luajava.bindClass("android.widget.ScrollView")
local LinearLayout        = luajava.bindClass("android.widget.LinearLayout")
local LayoutParams        = luajava.bindClass("android.widget.LinearLayout$LayoutParams")
local TextView            = luajava.bindClass("android.widget.TextView")
local Button              = luajava.bindClass("android.widget.Button")
local Bitmap              = luajava.bindClass("android.graphics.Bitmap")
local BitmapConfig        = luajava.bindClass("android.graphics.Bitmap$Config")
local CompressFormat      = luajava.bindClass("android.graphics.Bitmap$CompressFormat")
local BitmapDrawable      = luajava.bindClass("android.graphics.drawable.BitmapDrawable")
local Canvas              = luajava.bindClass("android.graphics.Canvas")
local Paint               = luajava.bindClass("android.graphics.Paint")
local PaintStyle          = luajava.bindClass("android.graphics.Paint$Style")
local Color               = luajava.bindClass("android.graphics.Color")
local FileOutputStream    = luajava.bindClass("java.io.FileOutputStream")
local LinkedBlockingQueue = luajava.bindClass("java.util.concurrent.LinkedBlockingQueue")

local W, H = 600, 420

-- Resolve Java enums explicitly via valueOf() for reliable LuaJ overload matching
local cfgArgb8888 = BitmapConfig:valueOf("ARGB_8888")
local styleFill   = PaintStyle:valueOf("FILL")
local styleStroke = PaintStyle:valueOf("STROKE")
local fmtPng      = CompressFormat:valueOf("PNG")

local bmp    = Bitmap:createBitmap(W, H, cfgArgb8888)
local canvas = luajava.new(Canvas, bmp)
local paint  = luajava.new(Paint)
paint:setAntiAlias(true)

local function drawScene()
    -- Background
    canvas:drawColor(Color:rgb(22, 27, 34))

    -- Grid lines
    paint:setStyle(styleStroke)
    paint:setStrokeWidth(1.0)
    paint:setColor(Color:rgb(48, 54, 61))
    for x = 0, W, 30 do
        canvas:drawLine(x, 0, x, H, paint)
    end
    for y = 0, H, 30 do
        canvas:drawLine(0, y, W, y, paint)
    end

    -- Solid filled box
    paint:setStyle(styleFill)
    paint:setColor(Color:rgb(31, 111, 235))
    canvas:drawRect(30, 30, 270, 160, paint)

    -- Outlined box border
    paint:setStyle(styleStroke)
    paint:setStrokeWidth(4.0)
    paint:setColor(Color:rgb(121, 192, 255))
    canvas:drawRect(30, 30, 270, 160, paint)

    -- Second outlined box with diagonal cross lines
    paint:setColor(Color:rgb(63, 185, 80))
    canvas:drawRect(310, 30, 570, 160, paint)
    canvas:drawLine(310, 30, 570, 160, paint)
    canvas:drawLine(570, 30, 310, 160, paint)

    -- Circle & horizontal divider line
    paint:setStyle(styleFill)
    paint:setColor(Color:rgb(210, 153, 34))
    canvas:drawCircle(150, 280, 65, paint)

    paint:setStyle(styleStroke)
    paint:setStrokeWidth(5.0)
    paint:setColor(Color:rgb(248, 81, 73))
    canvas:drawLine(260, 220, 570, 340, paint)
    canvas:drawLine(260, 340, 570, 220, paint)

    -- Text labels
    paint:setStyle(styleFill)
    paint:setColor(Color:rgb(255, 255, 255))
    paint:setTextSize(24.0)
    canvas:drawText("Filled + Border Box", 45, 102, paint)
    canvas:drawText("X-Box", 405, 102, paint)
    canvas:drawText("Drag finger on canvas to draw", 30, 395, paint)
end

drawScene()

local eventQueue = luajava.new(LinkedBlockingQueue)
local lastX, lastY = nil, nil

local touchPaint = luajava.new(Paint)
touchPaint:setAntiAlias(true)
touchPaint:setStyle(styleStroke)
touchPaint:setStrokeWidth(5.0)
touchPaint:setColor(Color:rgb(86, 211, 100))

ACTIVITY:runOnUiThread(luajava.createProxy("java.lang.Runnable", {
    run = function()
        local ok, err = pcall(function()
            local scroll = luajava.new(ScrollView, ACTIVITY)
            local root = luajava.new(LinearLayout, ACTIVITY)
            root:setOrientation(1) -- VERTICAL
            root:setPadding(16, 16, 16, 16)

            -- Render Bitmap via BitmapDrawable on a fixed-size surface view
            local surface = luajava.new(TextView, ACTIVITY)
            local drawable = luajava.new(BitmapDrawable, ACTIVITY:getResources(), bmp)
            surface:setBackgroundDrawable(drawable)
            surface:setWidth(W)
            surface:setHeight(H)

            surface:setOnTouchListener(luajava.createProxy("android.view.View$OnTouchListener", {
                onTouch = function(v, event)
                    local action = event:getAction()
                    local vw, vh = v:getWidth(), v:getHeight()
                    if vw <= 0 or vh <= 0 then return true end
                    local bx = (event:getX() / vw) * W
                    local by = (event:getY() / vh) * H

                    if action == 0 then -- ACTION_DOWN
                        lastX, lastY = bx, by
                    elseif action == 2 and lastX ~= nil then -- ACTION_MOVE
                        canvas:drawLine(lastX, lastY, bx, by, touchPaint)
                        lastX, lastY = bx, by
                        v:invalidate()
                    elseif action == 1 then -- ACTION_UP
                        lastX, lastY = nil, nil
                    end
                    return true
                end
            }))

            root:addView(surface, luajava.new(LayoutParams, W, H))

            local btnRow = luajava.new(LinearLayout, ACTIVITY)
            btnRow:setOrientation(0) -- HORIZONTAL

            local btnReset = luajava.new(Button, ACTIVITY)
            btnReset:setText("Reset")
            btnReset:setOnClickListener(luajava.createProxy("android.view.View$OnClickListener", {
                onClick = function(v)
                    drawScene()
                    surface:invalidate()
                end
            }))
            btnRow:addView(btnReset, luajava.new(LayoutParams, 0, -2, 1.0))

            local btnSave = luajava.new(Button, ACTIVITY)
            btnSave:setText("Save PNG")
            btnSave:setOnClickListener(luajava.createProxy("android.view.View$OnClickListener", {
                onClick = function(v)
                    eventQueue:offer("SAVE_PNG")
                end
            }))
            btnRow:addView(btnSave, luajava.new(LayoutParams, 0, -2, 1.0))

            local btnClose = luajava.new(Button, ACTIVITY)
            btnClose:setText("Close")
            btnClose:setOnClickListener(luajava.createProxy("android.view.View$OnClickListener", {
                onClick = function(v)
                    eventQueue:offer("CLOSE")
                end
            }))
            btnRow:addView(btnClose, luajava.new(LayoutParams, 0, -2, 1.0))

            root:addView(btnRow, luajava.new(LayoutParams, -1, -2))
            scroll:addView(root)

            local dlg = luajava.new(AlertDialogBuilder, ACTIVITY)
                :setTitle("LuaJ Drawing Canvas")
                :setView(scroll)
                :create()

            dlg:setOnDismissListener(luajava.createProxy("android.content.DialogInterface$OnDismissListener", {
                onDismiss = function(d)
                    eventQueue:offer("CLOSED")
                end
            }))

            -- Store dialog reference so the Close button can dismiss it
            _G.__activeCanvasDlg = dlg
            dlg:show()
        end)

        if not ok then
            log("Canvas UI error: " .. tostring(err))
            toast("Canvas UI error: " .. tostring(err), true)
            eventQueue:offer("CLOSED")
        end
    end
}))

while true do
    local msg = tostring(eventQueue:take())
    if msg == "CLOSED" then
        break
    elseif msg == "CLOSE" then
        ACTIVITY:runOnUiThread(luajava.createProxy("java.lang.Runnable", {
            run = function()
                if _G.__activeCanvasDlg ~= nil then
                    _G.__activeCanvasDlg:dismiss()
                    _G.__activeCanvasDlg = nil
                end
            end
        }))
    elseif msg == "SAVE_PNG" then
        local outDir = sdcard_dir() .. "/sync"
        mkdirs(outDir)
        local outPath = outDir .. "/lua_canvas.png"
        local fos = luajava.new(FileOutputStream, outPath)
        bmp:compress(fmtPng, 100, fos)
        fos:flush()
        fos:close()
        log("Saved PNG: " .. outPath)
        toast("Saved: " .. outPath, false)
    end
end

return "Canvas finished"