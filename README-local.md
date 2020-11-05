This is a bit of an octopus merge of several sources of bleeding-edge ghidra.  Proceed at your own risk.

NSA:
 https://github.com/NationalSecurityAgency/ghidra.git
  bleeding edge "upstream" ghidra
  git pull nsa master
xtensa language support, for esp8266 etc
 https://github.com/yath/ghidra-xtensa
 fixme: command (is a submodule)
videocore4 language, for raspberrypi firmware
 https://github.com/paolovagnero/ghidra/tree/vc4
 fixme: command (is a submodule)
windows quoting fixes
 https://github.com/xiaoyinl/ghidra/tree/fix_missing_quotes
 git pull xiaoyinl fix_missing_quotes
more PE known noreturn functions
 https://github.com/xiaoyinl/ghidra/tree/master
 git pull xiaoyinl master
decompiler output indentation pref
 https://github.com/NationalSecurityAgency/ghidra/pull/1938
 git pull js53867 feature/add-indentation-style-preference
decompiler: resolve constant code pointers
 https://github.com/GregoryMorse/ghidra/tree/patch-19
 git pull GregoryMorse patch-19
Add support for UEFI datatype libraries #501
 https://github.com/wrffrz/ghidra/tree/uefi-datatype
 git pull wrffrz uefi-datatype
UEFI Terse Executable loader
 https://github.com/wrffrz/ghidra/tree/uefi-te
 git pull wrffrz uefi-te
 FIXME: Not ready yet, massive compile failures
Autodetect the `___chkstk_ms` symbol #1889
 https://github.com/heinrich5991/ghidra/tree/pr_chkstk_ms
 git pull heinrich5991 pr_chkstk_ms
#573 Add shifted pointers #2189
 https://github.com/Popax21/ghidra
 git pull Popax21 master

 