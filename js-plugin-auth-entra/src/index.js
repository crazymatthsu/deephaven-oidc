/**
 * Deephaven web IDE authentication plugin for Microsoft Entra ID.
 *
 * Flow: authorization-code + PKCE via MSAL.js redirect. Opening /ide with no session redirects to
 * login.microsoftonline.com — enterprise SSO applies (a user already signed into their corporate
 * account gets silent sign-on) and Conditional Access injects the Microsoft Authenticator MFA
 * prompt per tenant policy. Back on the page, the access token is handed to Deephaven's
 * EntraOidcAuthenticationHandler.
 *
 * Server-side configuration surfaced to the browser via
 * -Dauthentication.client.configuration.list (see deephaven-entra-oidc-server/compose.yaml):
 *   authentication.oidc.entra.tenant-id      directory (tenant) ID
 *   authentication.oidc.entra.spa-client-id  SPA app registration client ID
 *   authentication.oidc.entra.scope          delegated API scope, e.g. api://<app-id>/access_as_user
 *
 * The registered SPA redirect URI must exactly match the IDE URL (origin + path), e.g.
 * http://localhost:10000/ide/ locally.
 */
import { createElement, useCallback, useMemo } from 'react';
import { AuthPluginBase } from '@deephaven/auth-plugins';
import { useBroadcastLoginListener } from '@deephaven/jsapi-components';
import {
  InteractionRequiredAuthError,
  PublicClientApplication,
} from '@azure/msal-browser';

const ENTRA_AUTH_TYPE = 'io.deephaven.oidc.entra.EntraOidcAuthenticationHandler';

const TENANT_ID_PROPERTY = 'authentication.oidc.entra.tenant-id';
const SPA_CLIENT_ID_PROPERTY = 'authentication.oidc.entra.spa-client-id';
const SCOPE_PROPERTY = 'authentication.oidc.entra.scope';

const LOG = '[@deephaven-oidc/js-plugin-auth-entra]';

/** Returned when the page is navigating away to the IdP; deliberately never settles. */
function navigatingAway() {
  return new Promise(() => {});
}

function Component({ authConfigValues, children }) {
  const getConfig = useCallback(
    key => {
      const value = authConfigValues.get(key);
      if (value == null || value === '') {
        throw new Error(
          `Entra config value ${key} not specified by the server. Browser login needs ` +
            'ENTRA_SPA_CLIENT_ID and ENTRA_WEB_SCOPE set on the Entra stack (see ' +
            'deephaven-entra-oidc-server/.env.example).'
        );
      }
      return value;
    },
    [authConfigValues]
  );

  const msal = useMemo(() => {
    const tenantId = getConfig(TENANT_ID_PROPERTY);
    const clientId = getConfig(SPA_CLIENT_ID_PROPERTY);
    return new PublicClientApplication({
      auth: {
        clientId,
        authority: `https://login.microsoftonline.com/${tenantId}`,
        // Must exactly match a redirect URI registered on the SPA platform of the app.
        redirectUri: window.location.origin + window.location.pathname,
      },
      // localStorage so an existing sign-in survives new tabs/reloads (enterprise SSO UX).
      cache: { cacheLocation: 'localStorage' },
    });
  }, [getConfig]);

  /**
   * Called by AuthPluginBase on mount (and again on re-login), i.e. on every page load —
   * including the load that returns from the Entra redirect.
   */
  const getLoginOptions = useCallback(async () => {
    const scope = getConfig(SCOPE_PROPERTY);
    const request = { scopes: [scope] };

    await msal.initialize();

    // Completes the code+PKCE round trip when the URL carries an auth response; null otherwise.
    const redirectResult = await msal.handleRedirectPromise();
    if (redirectResult != null && redirectResult.accessToken) {
      msal.setActiveAccount(redirectResult.account);
      console.log(
        LOG,
        `Signed in as ${redirectResult.account?.username}; logging into Deephaven...`
      );
      return { type: ENTRA_AUTH_TYPE, token: redirectResult.accessToken };
    }

    const account = redirectResult?.account ?? msal.getAllAccounts()[0];
    if (account == null) {
      console.log(LOG, 'No signed-in account; redirecting to Microsoft Entra ID...');
      await msal.loginRedirect(request);
      return navigatingAway();
    }

    msal.setActiveAccount(account);
    try {
      // MSAL serves from cache / refreshes silently; this also covers token renewal on
      // reconnect without a new MFA prompt while the refresh token is valid.
      const result = await msal.acquireTokenSilent({ ...request, account });
      console.log(LOG, `Signed in as ${account.username}; logging into Deephaven...`);
      return { type: ENTRA_AUTH_TYPE, token: result.accessToken };
    } catch (e) {
      if (e instanceof InteractionRequiredAuthError) {
        console.log(LOG, 'Silent renewal needs interaction; redirecting to Entra ID...');
        await msal.loginRedirect(request);
        return navigatingAway();
      }
      throw e;
    }
  }, [msal, getConfig]);

  const onLogin = useCallback(() => {
    console.debug(LOG, 'Received login event');
  }, []);

  const onLogout = useCallback(async () => {
    console.log(LOG, 'Logging out of Entra ID...');
    try {
      await msal.logoutRedirect();
    } catch (e) {
      console.error(LOG, 'Unable to log out of Entra ID:', e);
    }
  }, [msal]);
  useBroadcastLoginListener(onLogin, onLogout);

  return createElement(AuthPluginBase, { getLoginOptions }, children);
}

const AuthPluginEntra = {
  Component,
  isAvailable: authHandlers => authHandlers.includes(ENTRA_AUTH_TYPE),
};

export const AuthPlugin = AuthPluginEntra;
