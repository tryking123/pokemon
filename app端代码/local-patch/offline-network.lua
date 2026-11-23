-- 保留原客户端的协议编解码与会话分派，仅替换传输为进程内 JNI 调用。
local ffi = require("ffi")
local json = require("cjson")
ffi.cdef[[const char *pokemon_exchange(const char *request); int pokemon_exchange_size(void);]]
local native = ffi.load("pokemonoffline")
local link = { connected = false, generation = 0, pending = {} }

local function hex(value)
    return (value:gsub(".", function(c) return string.format("%02x", string.byte(c)) end))
end
local function unhex(value)
    assert(type(value) == "string" and #value % 2 == 0 and not value:find("[^0-9a-fA-F]"), "离线协议帧无效")
    return (value:gsub("..", function(c) return string.char(tonumber(c, 16)) end))
end
local function exchange(request)
    local pointer = native.pokemon_exchange(json.encode(request))
    assert(pointer ~= nil, "离线 App 桥未初始化")
    local result = json.decode(ffi.string(pointer, native.pokemon_exchange_size()))
    assert(result.ok, result.error or "离线 App 操作失败")
    for _, frame in ipairs(result.frames or {}) do
        link.pending[#link.pending + 1] = unhex(frame)
    end
    return result
end

-- 单次页面或存档操作失败只提示本地错误，不触发旧网络断线流程，也不自动重放领奖。
local lastFailure
local function report(failure)
    log("本地操作失败：" .. tostring(failure))
    -- 发布包可能关闭Unity日志，仍把不含请求正文的异常栈写到Android本地日志。
    pcall(function() exchange({op = "clientError", message = tostring(failure)}) end)
    if lastFailure == tostring(failure) then return end
    lastFailure = tostring(failure)
    pcall(function() load_popup.hide() end)
    pcall(function() label_popup.show("本地操作未完成，请重新打开页面后重试。") end)
end

function link:IsConnected() return self.connected end
function link:Connect(host, port)
    self.connected = false
    self.generation = self.generation + 1
    self.pending = {}
    exchange({op = "connect"})
    self.connected = true
    local generation = self.generation
    coroutine.start(function()
        coroutine.step(1)
        if not self.connected or self.generation ~= generation then return end
        Network.OnConnected()
        while self.connected and self.generation == generation do
            local ok, failure = pcall(function() exchange({op = "poll"}) end)
            if not ok then report(failure) end
            local frames = self.pending
            self.pending = {}
            for _, frame in ipairs(frames) do
                if not self.connected or self.generation ~= generation then break end
                -- 已提交消息逐条派发；单页回调失败不能丢掉随后到账的背包、邮件等消息。
                local delivered, problem = xpcall(function() Network.OnMessage(frame) end, debug.traceback)
                if not delivered then report(problem) end
            end
            coroutine.wait(0.25)
        end
    end)
end
function link:Close()
    self.connected = false
    self.generation = self.generation + 1
    self.pending = {}
    exchange({op = "disconnect"})
end
function link:Disconnect()
    self:Close()
    Network.OnDisconnected()
end
function link:SendRaw(payload)
    assert(self.connected, "离线角色尚未连接")
    local ok, failure = pcall(function() exchange({op = "request", payload = hex(payload)}) end)
    if not ok then report(failure) end
end

-- 启动后的选服历史、订单等非游戏帧也直接调用 App。
function link:Api(method, path, body, auth, contentType)
    return exchange({op = "api", method = method, path = path, body = body, authorization = auth, contentType = contentType})
end
rawset(_G, "PokemonOffline", link)
return link
