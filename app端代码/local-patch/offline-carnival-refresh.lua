-- 领取已由本地事务结算，直接重读权威列表，避免回调访问旧页面的嵌套行。
local ctrl = ...
if not ctrl.view or not ctrl.view.data then return end
ctrl.model.get_info()
