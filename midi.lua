-- ============================================================================
-- AndroLua MIDI Note Synthesizer & Tune Player (23 Tunes + Dropdown UI)
-- Converts standard MIDI note numbers (0..127) to Hz: f = 440 * 2^((n-69)/12)
-- ============================================================================

if ACTIVITY == nil then
    toast("Open AndroLua onscreen to use the MIDI Tune Player", true)
    return "No foreground Activity"
end

local AlertDialogBuilder  = luajava.bindClass("android.app.AlertDialog$Builder")
local ScrollView          = luajava.bindClass("android.widget.ScrollView")
local LinearLayout        = luajava.bindClass("android.widget.LinearLayout")
local LayoutParams        = luajava.bindClass("android.widget.LinearLayout$LayoutParams")
local TextView            = luajava.bindClass("android.widget.TextView")
local Spinner             = luajava.bindClass("android.widget.Spinner")
local ArrayAdapter        = luajava.bindClass("android.widget.ArrayAdapter")
local Button              = luajava.bindClass("android.widget.Button")
local R_layout            = luajava.bindClass("android.R$layout")
local ArrayList           = luajava.bindClass("java.util.ArrayList")
local LinkedBlockingQueue = luajava.bindClass("java.util.concurrent.LinkedBlockingQueue")

-- ============================================================================
-- 23 Tunes Defined as { midiNoteNumber, durationMs } (note = 0 means rest)
-- ============================================================================
local TUNES = {
    {
        name = "1. Close Encounters of the Third Kind",
        notes = { {67,480}, {69,480}, {65,480}, {53,480}, {60,950} }
    },
    {
        name = "2. Star Wars (Main Theme)",
        notes = {
            {62,160}, {62,160}, {62,160}, {67,750}, {74,750},
            {72,160}, {71,160}, {69,160}, {79,750}, {74,400},
            {72,160}, {71,160}, {69,160}, {79,750}, {74,400},
            {72,160}, {71,160}, {72,160}, {69,800}
        }
    },
    {
        name = "3. Imperial March (Darth Vader)",
        notes = {
            {67,420}, {67,420}, {67,420}, {63,310}, {70,130},
            {67,420}, {63,310}, {70,130}, {67,800}
        }
    },
    {
        name = "4. Harry Potter (Hedwig's Theme)",
        notes = {
            {71,260}, {76,390}, {79,140}, {78,260}, {76,520},
            {83,260}, {81,720}, {78,720}, {76,390}, {79,140},
            {78,260}, {75,520}, {77,260}, {71,850}
        }
    },
    {
        name = "5. Jaws Theme",
        notes = {
            {40,420}, {41,420}, {0,180},
            {40,340}, {41,340}, {0,120},
            {40,220}, {41,220}, {40,220}, {41,220},
            {40,140}, {41,140}, {40,140}, {41,140}, {40,140}, {41,450}
        }
    },
    {
        name = "6. Indiana Jones (Raiders March)",
        notes = {
            {64,260}, {65,130}, {67,260}, {72,680},
            {62,260}, {64,130}, {65,720},
            {67,260}, {69,130}, {71,260}, {77,680},
            {69,260}, {71,130}, {72,300}, {74,300}, {76,650}
        }
    },
    {
        name = "7. James Bond Theme",
        notes = {
            {64,320}, {66,160}, {66,160}, {66,320}, {66,320},
            {64,160}, {64,160}, {64,320},
            {64,320}, {67,160}, {67,160}, {67,320}, {67,320},
            {66,160}, {66,160}, {66,450}
        }
    },
    {
        name = "8. Mission: Impossible",
        notes = {
            {67,300}, {0,60}, {67,300}, {0,60}, {70,220}, {72,220},
            {67,300}, {0,60}, {67,300}, {0,60}, {65,220}, {66,220},
            {67,300}, {0,60}, {67,300}, {0,60}, {70,220}, {72,220},
            {67,550}
        }
    },
    {
        name = "9. The Godfather (Speak Softly Love)",
        notes = {
            {64,260}, {69,260}, {72,260}, {71,260}, {69,260}, {72,260},
            {69,260}, {71,260}, {69,260}, {65,260}, {67,260}, {64,750}
        }
    },
    {
        name = "10. The Pink Panther",
        notes = {
            {63,180}, {64,380}, {0,80}, {66,180}, {67,380}, {0,80},
            {63,180}, {64,220}, {66,180}, {67,220},
            {72,180}, {71,220}, {64,180}, {67,220}, {71,220}, {70,650}
        }
    },
    {
        name = "11. Super Mario Bros. (Overworld)",
        notes = {
            {76,150}, {76,150}, {0,150}, {76,150}, {0,150},
            {72,150}, {76,300}, {79,300}, {0,300}, {67,450}
        }
    },
    {
        name = "12. Legend of Zelda (Secret Chime)",
        notes = {
            {79,150}, {78,150}, {75,150}, {69,150},
            {68,150}, {76,150}, {80,150}, {84,550}
        }
    },
    {
        name = "13. Tetris (Korobeiniki)",
        notes = {
            {76,360}, {71,180}, {72,180}, {74,360}, {72,180}, {71,180},
            {69,360}, {69,180}, {72,180}, {76,360}, {74,180}, {72,180},
            {71,540}, {72,180}, {74,360}, {76,360}, {72,360}, {69,360}, {69,540}
        }
    },
    {
        name = "14. Nokia Tune (Gran Vals)",
        notes = {
            {76,160}, {74,160}, {66,320}, {68,320},
            {73,160}, {71,160}, {62,320}, {64,320},
            {71,160}, {69,160}, {61,320}, {64,320}, {69,650}
        }
    },
    {
        name = "15. Beethoven - Fur Elise",
        notes = {
            {76,200}, {75,200}, {76,200}, {75,200}, {76,200},
            {71,200}, {74,200}, {72,200}, {69,550},
            {60,200}, {64,200}, {69,200}, {71,550}
        }
    },
    {
        name = "16. Beethoven - Symphony No. 5",
        notes = {
            {67,170}, {67,170}, {67,170}, {63,750}, {0,180},
            {65,170}, {65,170}, {65,170}, {62,850}
        }
    },
    {
        name = "17. Beethoven - Ode to Joy",
        notes = {
            {64,300}, {64,300}, {65,300}, {67,300},
            {67,300}, {65,300}, {64,300}, {62,300},
            {60,300}, {60,300}, {62,300}, {64,300},
            {64,450}, {62,150}, {62,600}
        }
    },
    {
        name = "18. Mozart - Eine kleine Nachtmusik",
        notes = {
            {67,360}, {62,180}, {67,360}, {62,180},
            {67,180}, {62,180}, {67,180}, {71,180}, {74,600},
            {72,360}, {69,180}, {72,360}, {69,180},
            {72,180}, {69,180}, {66,180}, {69,180}, {62,600}
        }
    },
    {
        name = "19. Bach - Toccata in D Minor",
        notes = {
            {81,130}, {79,130}, {81,550},
            {79,140}, {77,140}, {76,140}, {74,140}, {73,280}, {74,700}
        }
    },
    {
        name = "20. Westminster Quarters (Big Ben)",
        notes = {
            {64,420}, {60,420}, {62,420}, {55,820}, {0,180},
            {55,420}, {62,420}, {64,420}, {60,820}
        }
    },
    {
        name = "21. Twinkle, Twinkle, Little Star",
        notes = {
            {60,300}, {60,300}, {67,300}, {67,300}, {69,300}, {69,300}, {67,600},
            {65,300}, {65,300}, {64,300}, {64,300}, {62,300}, {62,300}, {60,600}
        }
    },
    {
        name = "22. Happy Birthday to You",
        notes = {
            {60,220}, {60,120}, {62,340}, {60,340}, {65,340}, {64,650},
            {60,220}, {60,120}, {62,340}, {60,340}, {67,340}, {65,650}
        }
    },
    {
        name = "23. Hungarian Minor Scale (C D Eb F# G Ab B C)",
        notes = {
            {60,260}, {62,260}, {63,260}, {66,260},
            {67,260}, {68,260}, {71,260}, {72,520},
            {71,240}, {68,240}, {67,240}, {66,240},
            {63,240}, {62,240}, {60,650}
        }
    }
}

-- ============================================================================
-- MIDI Note Number <-> Note Name & Frequency Helpers
-- ============================================================================
local NOTE_NAMES = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" }

local function midiToName(n)
    if n <= 0 then return "REST" end
    local octave = math.floor(n / 12) - 1
    local pitch = NOTE_NAMES[(n % 12) + 1]
    return pitch .. tostring(octave)
end

local function midiToFreq(n)
    if n <= 0 then return 0.0 end
    return 440.0 * (2.0 ^ ((n - 69.0) / 12.0))
end

local function formatTuneDetails(tune)
    local names = {}
    local nums = {}
    local totalMs = 0
    for i = 1, #tune.notes do
        local n, ms = tune.notes[i][1], tune.notes[i][2]
        totalMs = totalMs + ms
        if n > 0 then
            names[#names + 1] = midiToName(n)
            nums[#nums + 1] = tostring(n)
        end
    end
    return string.format(
        "Notes: %s\nMIDI #: %s\nDuration: %.1f sec (%d events)",
        table.concat(names, " "),
        table.concat(nums, ", "),
        totalMs / 1000.0,
        #tune.notes
    )
end

-- ============================================================================
-- Pure-Lua 16-bit Mono PCM WAV Synthesizer (22050 Hz, warm harmonic tone)
-- ============================================================================
local function u16le(v)
    v = math.floor(v) % 65536
    return string.char(v % 256, math.floor(v / 256) % 256)
end

local function u32le(v)
    v = math.floor(v) % 4294967296
    local b0 = v % 256
    local b1 = math.floor(v / 256) % 256
    local b2 = math.floor(v / 65536) % 256
    local b3 = math.floor(v / 16777216) % 256
    return string.char(b0, b1, b2, b3)
end

local function renderTuneToWav(tune, tempoScale, outPath)
    local sampleRate = 22050
    local twoPi = 2.0 * math.pi
    local sin = math.sin
    local floor = math.floor
    local schar = string.char

    local chunks = {}
    local totalSamples = 0

    for idx = 1, #tune.notes do
        local midiNote = tune.notes[idx][1]
        local durSec = (tune.notes[idx][2] / 1000.0) / tempoScale
        local numSamples = math.max(1, floor(sampleRate * durSec))
        local freq = midiToFreq(midiNote)

        local attackSamples = math.min(floor(sampleRate * 0.015), floor(numSamples * 0.2))
        local releaseSamples = math.min(floor(sampleRate * 0.045), floor(numSamples * 0.35))

        local buf = {}
        local bIdx = 1

        if freq <= 0.0 then
            local silence = string.rep("\0\0", numSamples)
            chunks[#chunks + 1] = silence
            totalSamples = totalSamples + numSamples
        else
            local w1 = twoPi * freq / sampleRate
            local w2 = 2.0 * w1
            local w3 = 3.0 * w1

            for s = 0, numSamples - 1 do
                -- Smooth attack/decay envelope to eliminate clicks
                local env = 1.0
                if s < attackSamples and attackSamples > 0 then
                    env = s / attackSamples
                elseif s > (numSamples - releaseSamples) and releaseSamples > 0 then
                    env = (numSamples - s) / releaseSamples
                end

                -- Fundamental + 2nd & 3rd harmonics for warm synth/bell timbre
                local wave = 0.72 * sin(w1 * s) + 0.20 * sin(w2 * s) + 0.08 * sin(w3 * s)
                local pcm = floor(wave * env * 22000)
                if pcm < 0 then pcm = pcm + 65536 end

                buf[bIdx] = schar(pcm % 256, floor(pcm / 256) % 256)
                bIdx = bIdx + 1
            end
            chunks[#chunks + 1] = table.concat(buf)
            totalSamples = totalSamples + numSamples
        end
    end

    local pcmData = table.concat(chunks)
    local dataBytes = totalSamples * 2
    local header =
        "RIFF" .. u32le(36 + dataBytes) .. "WAVE" ..
        "fmt " .. u32le(16) .. u16le(1) .. u16le(1) ..
        u32le(sampleRate) .. u32le(sampleRate * 2) .. u16le(2) .. u16le(16) ..
        "data" .. u32le(dataBytes)

    local f = io.open(outPath, "wb")
    if not f then return false end
    f:write(header)
    f:write(pcmData)
    f:close()
    return true
end

-- ============================================================================
-- Native Android Dropdown UI
-- ============================================================================
local eventQueue = luajava.new(LinkedBlockingQueue)
local ui = {}
local TEMPOS = {
    { label = "1.0x (Normal)",  scale = 1.0 },
    { label = "0.75x (Slow)",   scale = 0.75 },
    { label = "1.25x (Fast)",   scale = 1.25 },
    { label = "1.5x (Faster)",  scale = 1.5 }
}

local function makeSpinner(items)
    local list = luajava.new(ArrayList)
    for i = 1, #items do list:add(items[i]) end
    local adapter = luajava.new(ArrayAdapter, ACTIVITY, R_layout.simple_spinner_item, list)
    adapter:setDropDownViewResource(R_layout.simple_spinner_dropdown_item)
    local sp = luajava.new(Spinner, ACTIVITY)
    sp:setAdapter(adapter)
    return sp
end

ACTIVITY:runOnUiThread(luajava.createProxy("java.lang.Runnable", {
    run = function()
        local scroll = luajava.new(ScrollView, ACTIVITY)
        local root = luajava.new(LinearLayout, ACTIVITY)
        root:setOrientation(1) -- VERTICAL
        root:setPadding(24, 18, 24, 18)

        local lblTune = luajava.new(TextView, ACTIVITY)
        lblTune:setText("Select MIDI Tune (23 Melodies):")
        root:addView(lblTune)

        local tuneNames = {}
        for i = 1, #TUNES do tuneNames[i] = TUNES[i].name end
        ui.spTune = makeSpinner(tuneNames)
        root:addView(ui.spTune, luajava.new(LayoutParams, -1, -2))

        local lblTempo = luajava.new(TextView, ACTIVITY)
        lblTempo:setText("Playback Speed / Tempo:")
        root:addView(lblTempo)

        local tempoLabels = {}
        for i = 1, #TEMPOS do tempoLabels[i] = TEMPOS[i].label end
        ui.spTempo = makeSpinner(tempoLabels)
        root:addView(ui.spTempo, luajava.new(LayoutParams, -1, -2))

        ui.tvInfo = luajava.new(TextView, ACTIVITY)
        ui.tvInfo:setPadding(8, 14, 8, 14)
        ui.tvInfo:setText(formatTuneDetails(TUNES[1]))
        root:addView(ui.tvInfo, luajava.new(LayoutParams, -1, -2))

        ui.spTune:setOnItemSelectedListener(luajava.createProxy("android.widget.AdapterView$OnItemSelectedListener", {
            onItemSelected = function(parent, view, pos, id)
                local t = TUNES[pos + 1]
                if t then ui.tvInfo:setText(formatTuneDetails(t)) end
            end,
            onNothingSelected = function(parent) end
        }))

        local row1 = luajava.new(LinearLayout, ACTIVITY)
        row1:setOrientation(0) -- HORIZONTAL

        local btnPlay = luajava.new(Button, ACTIVITY)
        btnPlay:setText("Play MIDI Tune")
        btnPlay:setOnClickListener(luajava.createProxy("android.view.View$OnClickListener", {
            onClick = function(v) eventQueue:offer("PLAY") end
        }))
        row1:addView(btnPlay, luajava.new(LayoutParams, 0, -2, 1.0))

        local btnStop = luajava.new(Button, ACTIVITY)
        btnStop:setText("Stop")
        btnStop:setOnClickListener(luajava.createProxy("android.view.View$OnClickListener", {
            onClick = function(v) eventQueue:offer("STOP") end
        }))
        row1:addView(btnStop, luajava.new(LayoutParams, 0, -2, 1.0))
        root:addView(row1, luajava.new(LayoutParams, -1, -2))

        local row2 = luajava.new(LinearLayout, ACTIVITY)
        row2:setOrientation(0)

        local btnSave = luajava.new(Button, ACTIVITY)
        btnSave:setText("Export WAV")
        btnSave:setOnClickListener(luajava.createProxy("android.view.View$OnClickListener", {
            onClick = function(v) eventQueue:offer("EXPORT") end
        }))
        row2:addView(btnSave, luajava.new(LayoutParams, 0, -2, 1.0))

        local btnClose = luajava.new(Button, ACTIVITY)
        btnClose:setText("Close")
        btnClose:setOnClickListener(luajava.createProxy("android.view.View$OnClickListener", {
            onClick = function(v) ui.dlg:dismiss() end
        }))
        row2:addView(btnClose, luajava.new(LayoutParams, 0, -2, 1.0))
        root:addView(row2, luajava.new(LayoutParams, -1, -2))

        scroll:addView(root)

        ui.dlg = luajava.new(AlertDialogBuilder, ACTIVITY)
            :setTitle("AndroLua MIDI Tune Synthesizer")
            :setView(scroll)
            :create()

        ui.dlg:setOnDismissListener(luajava.createProxy("android.content.DialogInterface$OnDismissListener", {
            onDismiss = function(d) eventQueue:offer("CLOSED") end
        }))
        ui.dlg:show()
    end
}))

local function getSelection()
    local res = {}
    local latch = luajava.new(java.util.concurrent.CountDownLatch, 1)
    ACTIVITY:runOnUiThread(luajava.createProxy("java.lang.Runnable", {
        run = function()
            res.tuneIdx  = ui.spTune:getSelectedItemPosition() + 1
            res.tempoIdx = ui.spTempo:getSelectedItemPosition() + 1
            latch:countDown()
        end
    }))
    latch:await()
    return TUNES[res.tuneIdx] or TUNES[1], TEMPOS[res.tempoIdx] or TEMPOS[1]
end

local outDir = sdcard_dir() .. "/androlua"
mkdirs(outDir)
local cacheWav = outDir .. "/androlua_midi_preview.wav"

while true do
    local cmd = tostring(eventQueue:take())
    if cmd == "CLOSED" then
        stop_sound()
        break
    elseif cmd == "STOP" then
        stop_sound()
    elseif cmd == "PLAY" then
        local tune, tempo = getSelection()
        log("Synthesizing & playing: " .. tune.name .. " @ " .. tempo.label)
        if renderTuneToWav(tune, tempo.scale, cacheWav) then
            local ok = play_sound(cacheWav, false)
            if not ok then
                log("play_sound failed for: " .. cacheWav)
                toast("Audio playback failed (check Media volume)", true)
            end
        else
            toast("Failed to synthesize audio", true)
        end
    elseif cmd == "EXPORT" then
        local tune, tempo = getSelection()
        local outDir = sdcard_dir() .. "/androlua"
        mkdirs(outDir)
        local safeName = string.gsub(tune.name, "[^%w]+", "_"):lower()
        local outFile = outDir .. "/" .. safeName .. ".wav"
        if renderTuneToWav(tune, tempo.scale, outFile) then
            log("Exported WAV: " .. outFile)
            toast("Saved: " .. outFile, true)
        end
    end
end

return "MIDI Player closed"