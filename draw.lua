-- ============================================================================
-- Drawing Lines, Boxes, Text & Interactive Touch Drawing in LuaJ
-- Uses: android.graphics.Bitmap, Canvas, Paint, Color + android.widget.ImageView
-- ============================================================================

if ACTIVITY == nil then
    toast("Open Asyncer UI to view canvas drawing", true)
    return "No foreground Activity"
end

local AlertDialogBuilder  = luajava.bindClass("android.app.AlertDialog$Builder")
local LinearLayout        = luajava.bindClass("android.widget.LinearLayout")
local LayoutParams        = luajava.bindClass("android.widget.LinearLayout$LayoutParams")
local ImageView           = luajava.bindClass("android.widget.ImageView")
local Button              = luajava.bindClass("android.widget.Button")
local TextView            = luajava.bindClass("android.widget.TextView")
local Bitmap              = luajava.bindClass("android.graphics.Bitmap")
local BitmapConfig        = luajava.bindClass("android.graphics.Bitmap$Config")
local CompressFormat      = luajava.bindClass("android.graphics.Bitmap$CompressFormat")
local Canvas              = luajava.bindClass("android.graphics.Canvas")
local Paint               = luajava.bindClass("android.graphics.Paint")
local PaintStyle          = luajava.bindClass("android.graphics.Paint$Style")
local RectF               = luajava.bindClass("android.graphics.RectF")
local FileOutputStream    = luajava.bindClass("java.io.FileOutputStream")
local LinkedBlockingQueue = luajava.bindClass("java.util.concurrent.LinkedBlockingQueue")

-- Helper to build 32-bit ARGB color integers without 64-bit sign issues in Lua 5.2 doubles
local function argb(a, r, g, b)
    local hi = ((a * 256) + r) * 65536
    local lo = (g * 256) + b
    local val = hi + lo
    if val >= 2147483648 then
        val = val - 4294967296
    end
    return val
end

local W, H = 640, 480
local bmp = Bitmap:createBitmap(W, H, BitmapConfig.ARGB_8888)
local canvas = luajava.new(Canvas, bmp)

local paint = luajava.new(Paint)
paint:setAntiAlias(true)

-- Function that draws lines, filled/outlined boxes, grid, and labels onto the Canvas
local function drawScene()
    -- 1. Background fill
    canvas:drawColor(argb(255, 24, 28, 36))

    -- 2. Grid lines
    paint:setStyle(PaintStyle.STROKE)
    paint:setStrokeWidth(1.0)
    paint:setColor(argb(255, 50, 58, 72))
    for x = 0, W, 40 do
        canvas:drawLine(x, 0, x, H, paint)
    end
    for y = 0, H, 40 do
        canvas:drawLine(0, y, W, y, paint)
    end

    -- 3. Solid filled box (left, top, right, bottom)
    paint:setStyle(PaintStyle.FILL)
    paint:setColor(argb(255, 30, 136, 229))
    canvas:drawRect(40, 40, 260, 180, paint)

    -- 4. Outlined box (stroke border around the filled box)
    paint:setStyle(PaintStyle.STROKE)
    paint:setStrokeWidth(4.0)
    paint:setColor(argb(255, 144, 202, 249))
    canvas:drawRect(40, 40, 260, 180, paint)

    -- 5. Rounded box
    paint:setStyle(PaintStyle.FILL)
    paint:setColor(argb(255, 67, 160, 71))
    local roundRect = luajava.new(RectF, 300, 40, 600, 180)
    canvas:drawRoundRect(roundRect, 20, 20, paint)

    -- 6. Diagonal & thick colored lines
    paint:setStyle(PaintStyle.STROKE)
    paint:setStrokeWidth(6.0)
    paint:setColor(argb(255, 255, 193, 7))
    canvas:drawLine(40, 220, 600, 220, paint)

    paint:setColor(argb(255, 239, 83, 80))
    canvas:drawLine(40, 240, 300, 430, paint)
    canvas:drawLine(300, 430, 600, 240, paint)

    -- 7. Labels inside boxes
    paint:setStyle(PaintStyle.FILL)
    paint:setColor(argb(255, 255, 255, 255))
    paint:setTextSize(26.0)
    canvas:drawText("Filled + Border Box", 55, 118, paint)
    canvas:drawText("Rounded Box", 365, 118, paint)
    paint:setTextSize(22.0)
    canvas:drawText("Drag finger below to draw freehand lines:", 40, 460, paint)
end

drawScene()

-- Display the Bitmap inside an ImageView dialog with Clear, Save PNG, and Touch Drawing
local eventQueue = luajava.new(LinkedBlockingQueue)
local imgViewRef = nil
local lastX, lastY = nil, nil

local drawPaint = luajava.new(Paint)
drawPaint:setAntiAlias(true)
drawPaint:setStyle(PaintStyle.STROKE)
drawPaint:setStrokeWidth(5.0)
drawPaint:setColor(argb(255, 0, 230, 118))

ACTIVITY:runOnUiThread(luajava.createProxy("java.lang.Runnable", {
    run = function()
        local root = luajava.new(LinearLayout, ACTIVITY)
        root:setOrientation(1) -- VERTICAL
        root:setPadding(16, 16, 16, 16)

        local imgView = luajava.new(ImageView, ACTIVITY)
        imgView:setAdjustViewBounds(true)
        imgView:setImageBitmap(bmp)
        imgViewRef = imgView

        -- Freehand touch drawing directly on the Bitmap canvas
        imgView:setOnTouchListener(luajava.createProxy("android.view.View$OnTouchListener", {
            onTouch = function(v, event)
                local action = event:getAction()
                -- Scale view touch coordinates to 640x480 bitmap coordinates
                local vw, vh = v:getWidth(), v:getHeight()
                if vw <= 0 or vh <= 0 then return true end
                local bx = (event:getX() / vw) * W
                local by = (event:getY() / vh) * H

                if action == 0 then -- ACTION_DOWN
                    lastX, lastY = bx, by
                elseif action == 2 and lastX ~= nil then -- ACTION_MOVE
                    canvas:drawLine(lastX, lastY, bx, by, drawPaint)
                    lastX, lastY = bx, by
                    v:invalidate()
                elseif action == 1 then -- ACTION_UP
                    lastX, lastY = nil, nil
                end
                return true
            end
        }))

        root:addView(imgView, luajava.new(LayoutParams, -1, -2))

        local btnRow = luajava.new(LinearLayout, ACTIVITY)
        btnRow:setOrientation(0) -- HORIZONTAL

        local btnClear = luajava.new(Button, ACTIVITY)
        btnClear:setText("Reset Canvas")
        btnClear:setOnClickListener(luajava.createProxy("android.view.View$OnClickListener", {
            onClick = function(v)
                drawScene()
                imgView:invalidate()
            end
        }))
        btnRow:addView(btnClear, luajava.new(LayoutParams, 0, -2, 1.0))

        local btnSave = luajava.new(Button, ACTIVITY)
        btnSave:setText("Save PNG")
        btnSave:setOnClickListener(luajava.createProxy("android.view.View$OnClickListener", {
            onClick = function(v)
                eventQueue:offer("SAVE_PNG")
            end
        }))
        btnRow:addView(btnSave, luajava.new(LayoutParams, 0, -2, 1.0))

        root:addView(btnRow, luajava.new(LayoutParams, -1, -2))

        local dlg = luajava.new(AlertDialogBuilder, ACTIVITY)
            :setTitle("LuaJ Vector & Bitmap Canvas")
            :setView(root)
            :setPositiveButton("Close", nil)
            :create()

        dlg:setOnDismissListener(luajava.createProxy("android.content.DialogInterface$OnDismissListener", {
            onDismiss = function(d)
                eventQueue:offer("CLOSED")
            end
        }))
        dlg:show()
    end
}))

while true do
    local msg = tostring(eventQueue:take())
    if msg == "CLOSED" then
        break
    elseif msg == "SAVE_PNG" then
        local outPath = sdcard_dir() .. "/sync/lua_canvas.png"
        mkdirs(sdcard_dir() .. "/sync")
        local fos = luajava.new(FileOutputStream, outPath)
        bmp:compress(CompressFormat.PNG, 100, fos)
        fos:flush()
        fos:close()
        log("Saved drawing to " .. outPath)
        toast("Saved: " .. outPath, false)
    end
end

return "Canvas closed"