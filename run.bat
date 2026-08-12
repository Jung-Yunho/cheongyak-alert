@echo off
rem ---------------------------------------------------------------------------
rem Cheongyak alert launcher. Register THIS file in Task Scheduler.
rem
rem   run.bat              normal run
rem   run.bat --dry        print only, do not send to Telegram
rem   run.bat --selftest   parser self-check
rem
rem NOTE: keep this file pure ASCII. "chcp" switches the console codepage
rem mid-file, and cmd.exe re-reads the batch by byte offset -- any non-ASCII
rem text above would desync the parser and run comments as commands.
rem Korean notes live in README.md instead.
rem
rem -Dfile.encoding=UTF-8 is required on JDK 17: it defaults to MS949 on Korean
rem Windows, so the single-file launcher would read this UTF-8 source as MS949
rem and mangle every Korean literal -- the IPO table lookup then silently
rem matches nothing. Harmless on JDK 18+, which already defaults to UTF-8.
rem ---------------------------------------------------------------------------

chcp 65001 >nul
java -Dfile.encoding=UTF-8 -Dcheongyak.dir="%~dp0." "%~dp0Cheongyak.java" %*
exit /b %ERRORLEVEL%
