# Preserve the OpenPocket prompt protocol across interactive bash/sudo/su shells.
# Ordinary SSH terminals have no protocol token and keep their usual settings.
case $- in *i*) ;; *) return ;; esac
[[ -t 0 && -z ${__openpocket_token-} ]] || return
__openpocket_token=$(/usr/bin/python3 /usr/local/lib/openpocket/shell-token.py)
[[ $__openpocket_token =~ ^[a-f0-9]{32}$ ]] || { unset __openpocket_token; return; }
__openpocket_prompt() {
    local __openpocket_rc=$?
    # Readline must not count the private protocol record as visible columns.
    PS1='\['$'\036openpocket_shell_'"$__openpocket_token"':${?}:${PWD}'$'\037''\]\u@\h:\w\$ '
    stty echo 2>/dev/null
    return "$__openpocket_rc"
}
case $(declare -p PROMPT_COMMAND 2>/dev/null) in
    'declare -a '*) PROMPT_COMMAND+=(__openpocket_prompt) ;;
    *) PROMPT_COMMAND="${PROMPT_COMMAND:+$PROMPT_COMMAND; }__openpocket_prompt" ;;
esac
PS2=''
export SHELL=/bin/bash
set -o emacs
