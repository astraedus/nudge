/**
 * A REAL signed-in `https://www.youtube.com/feed/channels` `ytInitialData`, captured live
 * 2026-11-01 (Chrome 148, desktop web client 2.20261029.11.00) from an account subscribed to
 * three channels, then SANITIZED and TRIMMED:
 *  - only the `contents` subtree is kept (the parser never reads anything else); the page
 *    header, topbar, responseContext and frameworkUpdates are gone;
 *  - tracking params, thumbnails, accessibility blobs and the subscribe-button payloads are
 *    stripped;
 *  - the three sort-chip continuation tokens (opaque per-session blobs) are replaced with
 *    `REDACTED_TOKEN_*` stand-ins. They stay in place ON PURPOSE: they are
 *    `continuationCommand`s that re-sort the list, not "load more", and the parser must not
 *    follow them.
 * No account data survives: the channels are public, and nothing names the viewer.
 *
 * What the live shape taught (and what the hand-built fixture had wrong): in a channelRenderer
 * here `subscriberCountText` carries the HANDLE ("@3blue1brown") and `videoCountText` carries
 * the SUBSCRIBER count ("8.73M subscribers"). Neither is read for identity: the handle comes
 * from `canonicalBaseUrl` / the navigation URL, the id from `channelId`.
 *
 * With zero subscriptions the same page is served with the chip header and NO
 * `sectionListRenderer.contents` at all (`capturedEmptyData`).
 *
 * Regenerate: capture the page signed in, then run the sanitizer described in
 * `extension/CLAUDE.md` (subscriptions section). Never commit an unsanitized capture.
 */

/* eslint-disable */

export const CAPTURED_CHANNELS = [
  { channelId: 'UCYO_jab_esuFRV4b17AJtAw', handle: '3blue1brown', displayName: '3Blue1Brown' },
  { channelId: 'UCHnyfMqiRRG1u-2MsSQLbXA', handle: 'veritasium', displayName: 'Veritasium' },
  { channelId: 'UCsXVk37bltHxD1rDPwtNM8Q', handle: 'kurzgesagt', displayName: 'Kurzgesagt – In a Nutshell' },
] as const;

export const CAPTURED_FEED_CHANNELS_DATA: Record<string, unknown> = {
  "contents": {
    "twoColumnBrowseResultsRenderer": {
      "tabs": [
        {
          "tabRenderer": {
            "selected": true,
            "content": {
              "sectionListRenderer": {
                "contents": [
                  {
                    "itemSectionRenderer": {
                      "contents": [
                        {
                          "shelfRenderer": {
                            "content": {
                              "expandedShelfContentsRenderer": {
                                "items": [
                                  {
                                    "channelRenderer": {
                                      "channelId": "UCYO_jab_esuFRV4b17AJtAw",
                                      "title": {
                                        "simpleText": "3Blue1Brown"
                                      },
                                      "navigationEndpoint": {
                                        "commandMetadata": {
                                          "webCommandMetadata": {
                                            "url": "/@3blue1brown",
                                            "webPageType": "WEB_PAGE_TYPE_CHANNEL",
                                            "apiUrl": "/youtubei/v1/browse"
                                          }
                                        },
                                        "browseEndpoint": {
                                          "browseId": "UCYO_jab_esuFRV4b17AJtAw",
                                          "canonicalBaseUrl": "/@3blue1brown"
                                        }
                                      },
                                      "descriptionSnippet": {
                                        "runs": [
                                          {
                                            "text": "My name is Grant Sanderson. Videos here cover a variety of topics in math, or adjacent fields like physics and CS, all with an emphasis on visualizing the core ideas. The goal is to use animation to help elucidate and motivate otherwise tricky topics, and for difficult problems to be made simple with changes in perspective.\n\nFor more information, other projects, FAQs, and inquiries see the website: https://www.3blue1brown.com"
                                          }
                                        ]
                                      },
                                      "shortBylineText": {
                                        "runs": [
                                          {
                                            "text": "3Blue1Brown",
                                            "navigationEndpoint": {
                                              "commandMetadata": {
                                                "webCommandMetadata": {
                                                  "url": "/@3blue1brown",
                                                  "webPageType": "WEB_PAGE_TYPE_CHANNEL",
                                                  "apiUrl": "/youtubei/v1/browse"
                                                }
                                              },
                                              "browseEndpoint": {
                                                "browseId": "UCYO_jab_esuFRV4b17AJtAw",
                                                "canonicalBaseUrl": "/@3blue1brown"
                                              }
                                            }
                                          }
                                        ]
                                      },
                                      "videoCountText": {
                                        "simpleText": "8.73M subscribers"
                                      },
                                      "subscriptionButton": {
                                        "subscribed": true
                                      },
                                      "ownerBadges": [
                                        {
                                          "metadataBadgeRenderer": {
                                            "icon": {
                                              "iconType": "CHECK_CIRCLE_THICK"
                                            },
                                            "style": "BADGE_STYLE_TYPE_VERIFIED",
                                            "tooltip": "Verified"
                                          }
                                        }
                                      ],
                                      "subscriberCountText": {
                                        "simpleText": "@3blue1brown"
                                      },
                                      "longBylineText": {
                                        "runs": [
                                          {
                                            "text": "3Blue1Brown",
                                            "navigationEndpoint": {
                                              "commandMetadata": {
                                                "webCommandMetadata": {
                                                  "url": "/@3blue1brown",
                                                  "webPageType": "WEB_PAGE_TYPE_CHANNEL",
                                                  "apiUrl": "/youtubei/v1/browse"
                                                }
                                              },
                                              "browseEndpoint": {
                                                "browseId": "UCYO_jab_esuFRV4b17AJtAw",
                                                "canonicalBaseUrl": "/@3blue1brown"
                                              }
                                            }
                                          }
                                        ]
                                      },
                                      "channelRendererStyle": "CHANNEL_RENDERER_STYLE__NARROW_THUMBNAIL_SPACING"
                                    }
                                  },
                                  {
                                    "channelRenderer": {
                                      "channelId": "UCHnyfMqiRRG1u-2MsSQLbXA",
                                      "title": {
                                        "simpleText": "Veritasium"
                                      },
                                      "navigationEndpoint": {
                                        "commandMetadata": {
                                          "webCommandMetadata": {
                                            "url": "/@veritasium",
                                            "webPageType": "WEB_PAGE_TYPE_CHANNEL",
                                            "apiUrl": "/youtubei/v1/browse"
                                          }
                                        },
                                        "browseEndpoint": {
                                          "browseId": "UCHnyfMqiRRG1u-2MsSQLbXA",
                                          "canonicalBaseUrl": "/@veritasium"
                                        }
                                      },
                                      "descriptionSnippet": {
                                        "runs": [
                                          {
                                            "text": "An element of truth - videos about science, education, and anything else we find interesting."
                                          }
                                        ]
                                      },
                                      "shortBylineText": {
                                        "runs": [
                                          {
                                            "text": "Veritasium",
                                            "navigationEndpoint": {
                                              "commandMetadata": {
                                                "webCommandMetadata": {
                                                  "url": "/@veritasium",
                                                  "webPageType": "WEB_PAGE_TYPE_CHANNEL",
                                                  "apiUrl": "/youtubei/v1/browse"
                                                }
                                              },
                                              "browseEndpoint": {
                                                "browseId": "UCHnyfMqiRRG1u-2MsSQLbXA",
                                                "canonicalBaseUrl": "/@veritasium"
                                              }
                                            }
                                          }
                                        ]
                                      },
                                      "videoCountText": {
                                        "simpleText": "21.4M subscribers"
                                      },
                                      "subscriptionButton": {
                                        "subscribed": true
                                      },
                                      "ownerBadges": [
                                        {
                                          "metadataBadgeRenderer": {
                                            "icon": {
                                              "iconType": "CHECK_CIRCLE_THICK"
                                            },
                                            "style": "BADGE_STYLE_TYPE_VERIFIED",
                                            "tooltip": "Verified"
                                          }
                                        }
                                      ],
                                      "subscriberCountText": {
                                        "simpleText": "@veritasium"
                                      },
                                      "longBylineText": {
                                        "runs": [
                                          {
                                            "text": "Veritasium",
                                            "navigationEndpoint": {
                                              "commandMetadata": {
                                                "webCommandMetadata": {
                                                  "url": "/@veritasium",
                                                  "webPageType": "WEB_PAGE_TYPE_CHANNEL",
                                                  "apiUrl": "/youtubei/v1/browse"
                                                }
                                              },
                                              "browseEndpoint": {
                                                "browseId": "UCHnyfMqiRRG1u-2MsSQLbXA",
                                                "canonicalBaseUrl": "/@veritasium"
                                              }
                                            }
                                          }
                                        ]
                                      },
                                      "channelRendererStyle": "CHANNEL_RENDERER_STYLE__NARROW_THUMBNAIL_SPACING"
                                    }
                                  },
                                  {
                                    "channelRenderer": {
                                      "channelId": "UCsXVk37bltHxD1rDPwtNM8Q",
                                      "title": {
                                        "simpleText": "Kurzgesagt – In a Nutshell"
                                      },
                                      "navigationEndpoint": {
                                        "commandMetadata": {
                                          "webCommandMetadata": {
                                            "url": "/@kurzgesagt",
                                            "webPageType": "WEB_PAGE_TYPE_CHANNEL",
                                            "apiUrl": "/youtubei/v1/browse"
                                          }
                                        },
                                        "browseEndpoint": {
                                          "browseId": "UCsXVk37bltHxD1rDPwtNM8Q",
                                          "canonicalBaseUrl": "/@kurzgesagt"
                                        }
                                      },
                                      "descriptionSnippet": {
                                        "runs": [
                                          {
                                            "text": "Animation videos explaining things with optimistic nihilism since 12,013.\n\nWe’re a team of illustrators, animators, number crunchers and one dog who aim to spark curiosity about science and the world we live in. To us nothing is boring if you tell a good story.\n\nFor updates on our videos and other news from the kurzgesagt universe follow us on Twitter, Facebook, Instagram and LinkedIn.\n"
                                          }
                                        ]
                                      },
                                      "shortBylineText": {
                                        "runs": [
                                          {
                                            "text": "Kurzgesagt – In a Nutshell",
                                            "navigationEndpoint": {
                                              "commandMetadata": {
                                                "webCommandMetadata": {
                                                  "url": "/@kurzgesagt",
                                                  "webPageType": "WEB_PAGE_TYPE_CHANNEL",
                                                  "apiUrl": "/youtubei/v1/browse"
                                                }
                                              },
                                              "browseEndpoint": {
                                                "browseId": "UCsXVk37bltHxD1rDPwtNM8Q",
                                                "canonicalBaseUrl": "/@kurzgesagt"
                                              }
                                            }
                                          }
                                        ]
                                      },
                                      "videoCountText": {
                                        "simpleText": "25.8M subscribers"
                                      },
                                      "subscriptionButton": {
                                        "subscribed": true
                                      },
                                      "ownerBadges": [
                                        {
                                          "metadataBadgeRenderer": {
                                            "icon": {
                                              "iconType": "CHECK_CIRCLE_THICK"
                                            },
                                            "style": "BADGE_STYLE_TYPE_VERIFIED",
                                            "tooltip": "Verified"
                                          }
                                        }
                                      ],
                                      "subscriberCountText": {
                                        "simpleText": "@kurzgesagt"
                                      },
                                      "longBylineText": {
                                        "runs": [
                                          {
                                            "text": "Kurzgesagt – In a Nutshell",
                                            "navigationEndpoint": {
                                              "commandMetadata": {
                                                "webCommandMetadata": {
                                                  "url": "/@kurzgesagt",
                                                  "webPageType": "WEB_PAGE_TYPE_CHANNEL",
                                                  "apiUrl": "/youtubei/v1/browse"
                                                }
                                              },
                                              "browseEndpoint": {
                                                "browseId": "UCsXVk37bltHxD1rDPwtNM8Q",
                                                "canonicalBaseUrl": "/@kurzgesagt"
                                              }
                                            }
                                          }
                                        ]
                                      },
                                      "channelRendererStyle": "CHANNEL_RENDERER_STYLE__NARROW_THUMBNAIL_SPACING"
                                    }
                                  }
                                ]
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                ],
                "header": {
                  "chipBarViewModel": {
                    "chips": [
                      {
                        "chipViewModel": {
                          "text": "Most relevant",
                          "displayType": "CHIP_VIEW_MODEL_DISPLAY_TYPE_DROP_DOWN",
                          "tapCommand": {
                            "innertubeCommand": {
                              "showSheetCommand": {
                                "panelLoadingStrategy": {
                                  "inlineContent": {
                                    "sheetViewModel": {
                                      "content": {
                                        "listViewModel": {
                                          "listItems": [
                                            {
                                              "listItemViewModel": {
                                                "title": {
                                                  "content": "Most relevant"
                                                },
                                                "isSelected": true,
                                                "entityKey": "channel_list_page_sort_chip_entity:Most relevant",
                                                "rendererContext": {
                                                  "commandContext": {
                                                    "onTap": {
                                                      "innertubeCommand": {
                                                        "commandExecutorCommand": {
                                                          "commands": [
                                                            {
                                                              "entityUpdateCommand": {
                                                                "entityBatchUpdate": {
                                                                  "mutations": [
                                                                    {
                                                                      "entityKey": "EiJjaGFubmVsX2xpc3RfcGFnZV9zb3J0X2NoaXBfZW50aXR5IPIDKAE%3D",
                                                                      "type": "ENTITY_MUTATION_TYPE_REPLACE",
                                                                      "payload": {
                                                                        "chipEntity": {
                                                                          "key": "EiJjaGFubmVsX2xpc3RfcGFnZV9zb3J0X2NoaXBfZW50aXR5IPIDKAE%3D",
                                                                          "text": "Most relevant"
                                                                        }
                                                                      }
                                                                    }
                                                                  ]
                                                                }
                                                              }
                                                            },
                                                            {
                                                              "commandMetadata": {
                                                                "webCommandMetadata": {
                                                                  "sendPost": true,
                                                                  "apiUrl": "/youtubei/v1/browse"
                                                                }
                                                              },
                                                              "continuationCommand": {
                                                                "token": "REDACTED_TOKEN_6387",
                                                                "request": "CONTINUATION_REQUEST_TYPE_BROWSE",
                                                                "command": {
                                                                  "showReloadUiCommand": {}
                                                                }
                                                              }
                                                            }
                                                          ]
                                                        }
                                                      }
                                                    }
                                                  }
                                                }
                                              }
                                            },
                                            {
                                              "listItemViewModel": {
                                                "title": {
                                                  "content": "New activity"
                                                },
                                                "entityKey": "channel_list_page_sort_chip_entity:New activity",
                                                "rendererContext": {
                                                  "commandContext": {
                                                    "onTap": {
                                                      "innertubeCommand": {
                                                        "commandExecutorCommand": {
                                                          "commands": [
                                                            {
                                                              "entityUpdateCommand": {
                                                                "entityBatchUpdate": {
                                                                  "mutations": [
                                                                    {
                                                                      "entityKey": "EiJjaGFubmVsX2xpc3RfcGFnZV9zb3J0X2NoaXBfZW50aXR5IPIDKAE%3D",
                                                                      "type": "ENTITY_MUTATION_TYPE_REPLACE",
                                                                      "payload": {
                                                                        "chipEntity": {
                                                                          "key": "EiJjaGFubmVsX2xpc3RfcGFnZV9zb3J0X2NoaXBfZW50aXR5IPIDKAE%3D",
                                                                          "text": "New activity"
                                                                        }
                                                                      }
                                                                    }
                                                                  ]
                                                                }
                                                              }
                                                            },
                                                            {
                                                              "commandMetadata": {
                                                                "webCommandMetadata": {
                                                                  "sendPost": true,
                                                                  "apiUrl": "/youtubei/v1/browse"
                                                                }
                                                              },
                                                              "continuationCommand": {
                                                                "token": "REDACTED_TOKEN_1644",
                                                                "request": "CONTINUATION_REQUEST_TYPE_BROWSE",
                                                                "command": {
                                                                  "showReloadUiCommand": {}
                                                                }
                                                              }
                                                            }
                                                          ]
                                                        }
                                                      }
                                                    }
                                                  }
                                                }
                                              }
                                            },
                                            {
                                              "listItemViewModel": {
                                                "title": {
                                                  "content": "A-Z"
                                                },
                                                "entityKey": "channel_list_page_sort_chip_entity:A-Z",
                                                "rendererContext": {
                                                  "commandContext": {
                                                    "onTap": {
                                                      "innertubeCommand": {
                                                        "commandExecutorCommand": {
                                                          "commands": [
                                                            {
                                                              "entityUpdateCommand": {
                                                                "entityBatchUpdate": {
                                                                  "mutations": [
                                                                    {
                                                                      "entityKey": "EiJjaGFubmVsX2xpc3RfcGFnZV9zb3J0X2NoaXBfZW50aXR5IPIDKAE%3D",
                                                                      "type": "ENTITY_MUTATION_TYPE_REPLACE",
                                                                      "payload": {
                                                                        "chipEntity": {
                                                                          "key": "EiJjaGFubmVsX2xpc3RfcGFnZV9zb3J0X2NoaXBfZW50aXR5IPIDKAE%3D",
                                                                          "text": "A-Z"
                                                                        }
                                                                      }
                                                                    }
                                                                  ]
                                                                }
                                                              }
                                                            },
                                                            {
                                                              "commandMetadata": {
                                                                "webCommandMetadata": {
                                                                  "sendPost": true,
                                                                  "apiUrl": "/youtubei/v1/browse"
                                                                }
                                                              },
                                                              "continuationCommand": {
                                                                "token": "REDACTED_TOKEN_3574",
                                                                "request": "CONTINUATION_REQUEST_TYPE_BROWSE",
                                                                "command": {
                                                                  "showReloadUiCommand": {}
                                                                }
                                                              }
                                                            }
                                                          ]
                                                        }
                                                      }
                                                    }
                                                  }
                                                }
                                              }
                                            }
                                          ]
                                        }
                                      }
                                    }
                                  }
                                }
                              }
                            }
                          },
                          "accessibilityLabel": "Most relevant",
                          "chipEntityKey": "EiJjaGFubmVsX2xpc3RfcGFnZV9zb3J0X2NoaXBfZW50aXR5IPIDKAE%3D"
                        }
                      }
                    ]
                  }
                }
              }
            },
            "tabIdentifier": "FEchannels"
          }
        }
      ]
    }
  }
};

/** The (mutable) `sectionListRenderer` of a page shaped like the capture. */
export function sectionListOf(data: Record<string, unknown>): Record<string, unknown> {
  const section = (data as any).contents?.twoColumnBrowseResultsRenderer?.tabs?.[0]?.tabRenderer?.content
    ?.sectionListRenderer;
  if (section === undefined || section === null) throw new Error('not a /feed/channels shape');
  return section as Record<string, unknown>;
}

/** The captured page as a signed-in account with no subscriptions gets it. */
export function capturedEmptyData(): Record<string, unknown> {
  const data = structuredClone(CAPTURED_FEED_CHANNELS_DATA);
  delete sectionListOf(data).contents;
  return data;
}
